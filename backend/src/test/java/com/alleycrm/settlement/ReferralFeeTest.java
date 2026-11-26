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
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
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

/** D15 소개 수수료(FR-STL-02 #1 ~ #6, FR-STL-01 #8 건당 한 번, NFR-04 #1 생길 때 수수료율 기록). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReferralFeeTest {

    /** 2026-11-30 20:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-30T11:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    SettingService settings;

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
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL");
        for (String t : new String[] {"referral_fee", "wallet_tx", "wallet", "coupon", "coupon_policy", "course_proposal",
                "reservation", "setting", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_STL_02_1_소개_예약은_4퍼센트가_가게에서_기금으로_거래_2건() throws Exception {
        long r = referral();
        visit(r, 30000).andExpect(status().isOk());

        Map<String, Object> fee = fee(r);
        assertThat(fee.get("fee_amount")).isEqualTo(1200);
        assertThat(fee.get("fee_base")).isEqualTo(30000);
        assertThat(fee.get("sender_store_id")).isEqualTo(storeA);
        assertThat(fee.get("settlement_month")).isEqualTo("2026-11");
        assertThat(balance("store", storeB)).isEqualTo(-1200);
        assertThat(balance("fund", region)).isEqualTo(1200);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE reservation_id = " + r)).isEqualTo(2);
    }

    @Test
    void FR_STL_02_2_정액_쿠폰은_할인_후_금액에_수수료_쿠폰_비용_따로() throws Exception {
        long r = referral();
        applyCoupon(r, "INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count, confirmed_at)"
                + " VALUES (" + storeB + ", 'fixed_discount', 5000, 10, 1, NOW(6))");
        visit(r, 30000).andExpect(status().isOk());

        Map<String, Object> fee = fee(r);
        assertThat(fee.get("coupon_discount")).isEqualTo(5000);
        assertThat(fee.get("fee_base")).isEqualTo(25000);
        assertThat(fee.get("fee_amount")).isEqualTo(1000);
        assertThat(fee.get("coupon_cost")).isEqualTo(5000);
    }

    @Test
    void FR_STL_02_3_서비스_쿠폰은_원래_금액에_수수료_환산_금액은_쿠폰_비용() throws Exception {
        long r = referral();
        applyCoupon(r, "INSERT INTO coupon_policy (store_id, benefit_type, service_name, service_value, total_quantity, issued_count, confirmed_at)"
                + " VALUES (" + storeB + ", 'service', '음료', 4000, 10, 1, NOW(6))");
        visit(r, 30000).andExpect(status().isOk());

        Map<String, Object> fee = fee(r);
        assertThat(fee.get("fee_amount")).isEqualTo(1200);
        assertThat(fee.get("coupon_cost")).isEqualTo(4000);
    }

    @Test
    void FR_STL_02_4_소개가_아닌_예약은_수수료_없음() throws Exception {
        long r = reservation(null, null);
        visit(r, 30000).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM referral_fee")).isZero();
        assertThat(count("SELECT COUNT(*) FROM wallet_tx")).isZero();
    }

    @Test
    void FR_STL_02_5_정률_쿠폰() throws Exception {
        long r = referral();
        applyCoupon(r, "INSERT INTO coupon_policy (store_id, benefit_type, discount_rate, total_quantity, issued_count, confirmed_at)"
                + " VALUES (" + storeB + ", 'percent_discount', 10, 10, 1, NOW(6))");
        visit(r, 30000).andExpect(status().isOk());

        Map<String, Object> fee = fee(r);
        assertThat(fee.get("fee_amount")).isEqualTo(1080);
        assertThat(fee.get("coupon_cost")).isEqualTo(3000);
    }

    @Test
    void FR_STL_02_6_가게_잔액이_모자라도_빼고_마이너스() throws Exception {
        jdbc.update("INSERT INTO wallet (owner_type, owner_id, balance) VALUES ('store', ?, 500)", storeB);
        long r = referral();
        visit(r, 30000).andExpect(status().isOk());
        assertThat(balance("store", storeB)).isEqualTo(-700);
    }

    @Test
    void NFR_04_1_수수료율을_바꾸면_다음부터_적용되고_생긴_수수료는_그대로() throws Exception {
        long first = referral();
        visit(first, 30000).andExpect(status().isOk());
        settings.set(region, SettingKey.REFERRAL_FEE_RATE, "0.05");
        long second = referral();
        visit(second, 30000).andExpect(status().isOk());

        assertThat(fee(first).get("fee_amount")).isEqualTo(1200);
        assertThat(fee(second).get("fee_amount")).isEqualTo(1500);
    }

    @Test
    void 수수료는_1원_미만_버림() throws Exception {
        long r = referral();
        visit(r, 33333).andExpect(status().isOk());
        assertThat(fee(r).get("fee_amount")).isEqualTo(1333); // 1,333.32
    }

    @Test
    void B8_방문_처리_응답과_목록_상세에_소개_확정과_수수료() throws Exception {
        long r = referral();
        visit(r, 30000).andExpect(status().isOk())
                .andExpect(jsonPath("$.referralConfirmed").value(true))
                .andExpect(jsonPath("$.referralFee").value(1200));
        mvc.perform(get("/api/v1/owner/reservations/" + r).with(user("ownerB")))
                .andExpect(jsonPath("$.referralConfirmed").value(true))
                .andExpect(jsonPath("$.referralFee").value(1200))
                .andExpect(jsonPath("$.senderStoreId").doesNotExist());
        mvc.perform(get("/api/v1/owner/reservations").param("date", "2026-11-30").with(user("ownerB")))
                .andExpect(jsonPath("$.items[?(@.id == '" + r + "')].referralFee").value(1200));
    }

    @Test
    void B8_소개가_아니면_확정_아님_수수료_null() throws Exception {
        long r = reservation(null, null);
        visit(r, 30000).andExpect(status().isOk())
                .andExpect(jsonPath("$.referralConfirmed").value(false))
                .andExpect(jsonPath("$.referralFee").doesNotExist());
    }

    private long referral() {
        long base = reservation(null, null, storeA);
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeB + ", NOW(6))");
        return reservation(proposal, storeA);
    }

    private long reservation(Long proposal, Long sender) {
        return reservation(proposal, sender, storeB);
    }

    private long reservation(Long proposal, Long sender, long store) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at,"
                + " course_proposal_id, sender_store_id) VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', "
                + store + ", " + customer + ", '2026-11-30 10:00:00', 2, 'confirmed', '2026-11-29 00:00:00', " + proposal + ", " + sender + ")");
    }

    private void applyCoupon(long reservation, String policySql) {
        long policy = insert(policySql);
        jdbc.update("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id)"
                + " VALUES (?, ?, ?, NOW(6), NOW(6) + INTERVAL 7 DAY, 'applied', ?)", customer, policy, storeB, reservation);
    }

    private ResultActions visit(long id, int amount) throws Exception {
        return mvc.perform(post("/api/v1/owner/reservations/" + id + "/visit").with(TestCsrf.csrf(mvc)).with(user("ownerB"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visitAmount\": " + amount + "}"));
    }

    private Map<String, Object> fee(long reservation) {
        return jdbc.queryForMap("SELECT * FROM referral_fee WHERE reservation_id = ?", reservation);
    }

    private long balance(String type, long id) {
        Long b = jdbc.queryForObject("SELECT COALESCE(MAX(balance), 0) FROM wallet WHERE owner_type = ? AND owner_id = ?", Long.class, type, id);
        return b == null ? 0 : b;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
