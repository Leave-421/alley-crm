package com.alleycrm.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** D22 금액 정정 · D23 늦은 방문 처리(FR-STL-09 #1 ~ #5, FR-RSV-02 #4, 11-25 (5) · 11-27 (2)). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorrectionAndLateVisitTest {

    /** 2026-11-30 20:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-30T11:00:00Z");
    /** 2026-12-03 12:00 Asia/Seoul: 11월 정산 확정 전 */
    static final Instant DEC_3 = Instant.parse("2026-12-03T03:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long region;
    long storeA;
    long storeB;
    long customer;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'B', 'pub', 'center', 'approved')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerB', 'x', 'owner', ?)", storeB);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, region_id) VALUES ('assoc', 'x', 'association', ?)", region);
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL");
        for (String t : new String[] {"visit_amount_correction", "visit_report", "notification", "referral_fee", "wallet_tx", "wallet",
                "coupon", "coupon_policy", "course_proposal", "settlement", "reservation", "setting", "customer", "account", "store",
                "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_STL_09_1_정정하면_원래_기록은_그대로_차액_400원이_가게에서_기금으로() throws Exception {
        long r = referral("confirmed", null);
        visit(r, 30000).andExpect(status().isOk());
        report(r, "amount", "valid");
        clock.setTo(DEC_3);

        correct(r, 40000).andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAmount").value(30000))
                .andExpect(jsonPath("$.correctedAmount").value(40000))
                .andExpect(jsonPath("$.feeDifference").value(400))
                .andExpect(jsonPath("$.settlementMonth").value("2026-11"));

        assertThat(jdbc.queryForObject("SELECT visit_amount FROM reservation WHERE id = ?", Integer.class, r)).isEqualTo(30000);
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM referral_fee WHERE reservation_id = ?", Integer.class, r)).isEqualTo(1200);
        assertThat(balance("store", storeB)).isEqualTo(-1600);
        assertThat(balance("fund", region)).isEqualTo(1600);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE type IN ('fee_difference_charged', 'fee_difference_deposited')")).isEqualTo(2);
        Long txId = jdbc.queryForObject("SELECT wallet_tx_id FROM visit_amount_correction WHERE reservation_id = ?", Long.class, r);
        assertThat(jdbc.queryForObject("SELECT type FROM wallet_tx WHERE id = ?", String.class, txId)).isEqualTo("fee_difference_charged");

        // #3: 차액은 원래 달(11월) 정산에 들어간다
        mvc.perform(get("/api/v1/association/settlements/2026-11").with(user("assoc")))
                .andExpect(jsonPath("$.totalFee").value(1600))
                .andExpect(jsonPath("$.stores[0].feeDifference").value(400));
        mvc.perform(get("/api/v1/owner/referral-fees").param("month", "2026-11").with(user("ownerB")))
                .andExpect(jsonPath("$.totalFee").value(1600))
                .andExpect(jsonPath("$.items[0].feeAmount").value(1200))
                .andExpect(jsonPath("$.items[0].feeDifference").value(400));
    }

    @Test
    void 정정_금액이_줄면_차액을_기금에서_가게로_돌려준다() throws Exception {
        long r = referral("confirmed", null);
        visit(r, 30000).andExpect(status().isOk());
        report(r, "amount", "valid");

        correct(r, 20000).andExpect(status().isOk()).andExpect(jsonPath("$.feeDifference").value(-400));

        assertThat(balance("store", storeB)).isEqualTo(-800);
        assertThat(balance("fund", region)).isEqualTo(800);
        Long txId = jdbc.queryForObject("SELECT wallet_tx_id FROM visit_amount_correction WHERE reservation_id = ?", Long.class, r);
        assertThat(jdbc.queryForObject("SELECT type FROM wallet_tx WHERE id = ?", String.class, txId)).isEqualTo("fee_difference_refunded");
    }

    @Test
    void 기금이_모자라면_줄어든_정정은_거절되고_아무것도_남지_않는다() throws Exception {
        long r = referral("confirmed", null);
        visit(r, 30000).andExpect(status().isOk());
        report(r, "amount", "valid");
        jdbc.update("UPDATE wallet SET balance = 100 WHERE owner_type = 'fund' AND owner_id = ?", region);

        correct(r, 20000).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("insufficient_balance"));

        assertThat(count("SELECT COUNT(*) FROM visit_amount_correction")).isZero();
        assertThat(balance("store", storeB)).isEqualTo(-1200);
    }

    @Test
    void FR_STL_09_4_맞음_신고가_없거나_이미_쓴_신고면_정정_거절() throws Exception {
        long r = referral("confirmed", null);
        visit(r, 30000).andExpect(status().isOk());
        correct(r, 40000).andExpect(status().isConflict());           // 신고 없음
        report(r, "amount", "invalid");
        correct(r, 40000).andExpect(status().isConflict());           // '아님'
        report(r, "not_processed", "valid");
        correct(r, 40000).andExpect(status().isConflict());           // 다른 종류
        report(r, "amount", "valid");
        correct(r, 40000).andExpect(status().isOk());
        correct(r, 50000).andExpect(status().isConflict());           // 신고 한 건에 정정 한 번
        assertThat(count("SELECT COUNT(*) FROM visit_amount_correction")).isEqualTo(1);
    }

    @Test
    void 소개가_아닌_예약은_정정_기록만_남고_돈은_움직이지_않는다() throws Exception {
        long r = reservation(null, null, "confirmed", null);
        visit(r, 30000).andExpect(status().isOk());
        report(r, "amount", "valid");

        correct(r, 40000).andExpect(status().isOk()).andExpect(jsonPath("$.feeDifference").value(0))
                .andExpect(jsonPath("$.settlementMonth").value("2026-11"));
        assertThat(count("SELECT COUNT(*) FROM wallet_tx")).isZero();
    }

    @Test
    void FR_STL_09_2_3_5_늦은_방문_처리는_자동_취소에서_방문으로_수수료는_원래_달_쿠폰은_적용됨으로() throws Exception {
        long r = referral("canceled", "auto_canceled");
        long coupon = applyCoupon(r, "apply_canceled");
        report(r, "not_processed", "valid");
        clock.setTo(DEC_3);

        lateVisit(r, 30000).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visited"))
                .andExpect(jsonPath("$.cancelReason").doesNotExist())
                .andExpect(jsonPath("$.visitAmount").value(30000))
                .andExpect(jsonPath("$.referralConfirmed").value(true))
                .andExpect(jsonPath("$.referralFee").value(1000)); // 정액 5,000원 쿠폰: 25,000 × 4%

        assertThat(jdbc.queryForObject("SELECT settlement_month FROM referral_fee WHERE reservation_id = ?", String.class, r))
                .isEqualTo("2026-11");
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon)).isEqualTo("applied");
        assertThat(balance("store", storeB)).isEqualTo(-1000);
    }

    @Test
    void FR_RSV_02_4_맞음_미처리_신고가_없거나_자동_취소가_아니면_늦은_방문_처리_거절() throws Exception {
        long noReport = referral("canceled", "auto_canceled");
        lateVisit(noReport, 30000).andExpect(status().isConflict());
        report(noReport, "not_processed", "invalid");
        lateVisit(noReport, 30000).andExpect(status().isConflict());

        long customerCanceled = referral("canceled", "customer_canceled");
        report(customerCanceled, "not_processed", "valid");
        lateVisit(customerCanceled, 30000).andExpect(status().isConflict());

        long confirmed = referral("confirmed", null);
        report(confirmed, "not_processed", "valid");
        lateVisit(confirmed, 30000).andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM referral_fee")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, noReport)).isEqualTo("canceled");
    }

    private long referral(String status, String cancelReason) {
        long base = reservation(null, null, "confirmed", null, storeA);
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeB + ", NOW(6))");
        return reservation(proposal, storeA, status, cancelReason);
    }

    private long reservation(Long proposal, Long sender, String status, String cancelReason) {
        return reservation(proposal, sender, status, cancelReason, storeB);
    }

    private long reservation(Long proposal, Long sender, String status, String cancelReason, long store) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,"
                + " requested_at, course_proposal_id, sender_store_id) VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8)
                + "', " + store + ", " + customer + ", '2026-11-30 10:00:00', 2, '" + status + "', "
                + (cancelReason == null ? "NULL" : "'" + cancelReason + "'") + ", '2026-11-29 00:00:00', " + proposal + ", " + sender + ")");
    }

    private long applyCoupon(long reservation, String status) {
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count, confirmed_at)"
                + " VALUES (" + storeB + ", 'fixed_discount', 5000, 10, 1, NOW(6))");
        return insert("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id)"
                + " VALUES (" + customer + ", " + policy + ", " + storeB + ", NOW(6), NOW(6) + INTERVAL 30 DAY, '" + status + "', "
                + reservation + ")");
    }

    private void report(long reservation, String type, String result) {
        jdbc.update("""
                INSERT INTO visit_report (reservation_id, customer_id, type, status, result, handled_at, created_at)
                VALUES (?, ?, ?, 'done', ?, NOW(6), NOW(6))
                """, reservation, customer, type, result);
    }

    private ResultActions visit(long id, int amount) throws Exception {
        return ownerPost(id, "visit", "{\"visitAmount\": " + amount + "}");
    }

    private ResultActions lateVisit(long id, int amount) throws Exception {
        return ownerPost(id, "late-visit", "{\"visitAmount\": " + amount + "}");
    }

    private ResultActions correct(long id, int amount) throws Exception {
        return ownerPost(id, "amount-correction", "{\"correctedAmount\": " + amount + "}");
    }

    private ResultActions ownerPost(long id, String action, String body) throws Exception {
        return mvc.perform(post("/api/v1/owner/reservations/" + id + "/" + action).with(TestCsrf.csrf(mvc)).with(user("ownerB"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long balance(String type, long id) {
        Map<String, Object> row = jdbc.queryForList("SELECT balance FROM wallet WHERE owner_type = ? AND owner_id = ?", type, id)
                .stream().findFirst().orElse(Map.of("balance", 0L));
        return ((Number) row.get("balance")).longValue();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
