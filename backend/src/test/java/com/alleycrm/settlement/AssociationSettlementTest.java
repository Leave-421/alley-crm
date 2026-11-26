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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** D17 월 정산 · 확정(FR-STL-05 #1 ~ #5). 수수료 행은 D15가 만드는 모양 그대로 넣는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssociationSettlementTest {

    /** 2026-12-01 10:00 Asia/Seoul: 11월이 끝남 */
    static final Instant NOW = Instant.parse("2026-12-01T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long region;
    long other;
    long storeA;
    long storeB;
    long customer;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('권역')");
        other = insert("INSERT INTO region (name) VALUES ('다른 권역')");
        storeA = store(region, "A");
        storeB = store(region, "B");
        long otherStore = store(other, "X");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, region_id) VALUES ('assoc1', 'x', 'association', ?)", region);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerB', 'x', 'owner', ?)", storeB);
        customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', NULL)");
        // B가 11월에 낸 소개 수수료 2건(계산 금액 25,000 · 30,000), 11-30 방문 건 포함
        fee(storeB, storeA, "2026-11", 25000, 1000);
        fee(storeB, storeA, "2026-11", 30000, 1200);
        fee(storeB, storeA, "2026-12", 10000, 400);
        fee(otherStore, otherStore, "2026-11", 99000, 3960);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"visit_report", "referral_fee", "settlement", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_STL_05_1_3_방문_처리된_달로_집계하고_가게별_평균_방문_금액() throws Exception {
        mvc.perform(get("/api/v1/association/settlements/2026-11").with(user("assoc1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("aggregated"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalFee").value(2200))
                .andExpect(jsonPath("$.stores.length()").value(1)) // 다른 권역 제외
                .andExpect(jsonPath("$.stores[0].storeName").value("B"))
                .andExpect(jsonPath("$.stores[0].avgVisitAmount").value(27500))
                .andExpect(jsonPath("$.canConfirm").value(true));
    }

    @Test
    void FR_STL_05_2_방문_전에_취소된_소개_예약은_수수료가_없어_빠진다() throws Exception {
        insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason, requested_at)"
                + " VALUES ('CANCEL01', " + storeB + ", " + customer + ", '2026-11-20 09:00:00', 2, 'canceled', 'customer_canceled', '2026-11-19 00:00:00')");
        mvc.perform(get("/api/v1/association/settlements/2026-11").with(user("assoc1")))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void FR_STL_05_4_확정하면_상태와_시각이_남고_잔액은_그대로() throws Exception {
        mvc.perform(post("/api/v1/association/settlements/2026-11/confirm").with(TestCsrf.csrf(mvc)).with(user("assoc1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.confirmedAt").value(org.hamcrest.Matchers.startsWith("2026-12-01T01:00:00")))
                .andExpect(jsonPath("$.canConfirm").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE settlement_month = '2026-11' AND settlement_id IS NOT NULL"
                + " AND receiving_store_id = " + storeB, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_tx", Integer.class)).isZero();

        // 두 번 확정하면 conflict
        mvc.perform(post("/api/v1/association/settlements/2026-11/confirm").with(TestCsrf.csrf(mvc)).with(user("assoc1")))
                .andExpect(status().isConflict());
    }

    @Test
    void FR_STL_05_5_처리되지_않은_신고가_있으면_확정_거절() throws Exception {
        long r = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)"
                + " VALUES ('REPORT01', " + storeB + ", " + customer + ", '2026-11-15 09:00:00', 2, 'visited', '2026-11-14 00:00:00')");
        jdbc.update("INSERT INTO visit_report (reservation_id, customer_id, type, status, created_at) VALUES (?, ?, 'amount', 'received', NOW(6))", r, customer);

        mvc.perform(get("/api/v1/association/settlements/2026-11").with(user("assoc1")))
                .andExpect(jsonPath("$.unprocessedReportCount").value(1))
                .andExpect(jsonPath("$.canConfirm").value(false));
        mvc.perform(post("/api/v1/association/settlements/2026-11/confirm").with(TestCsrf.csrf(mvc)).with(user("assoc1")))
                .andExpect(status().isConflict());
    }

    @Test
    void 끝나지_않은_달은_확정할_수_없고_사장님은_볼_수_없다() throws Exception {
        mvc.perform(post("/api/v1/association/settlements/2026-12/confirm").with(TestCsrf.csrf(mvc)).with(user("assoc1")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/association/settlements/2026-11").with(user("ownerB")))
                .andExpect(status().isForbidden());
    }

    private long store(long r, String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + r + ", '" + name + "', 'cafe', 'center', 'approved')");
    }

    private void fee(long receiving, long sender, String month, int base, int fee) {
        long r = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, visited_at, visit_amount)"
                + " VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + receiving + ", " + customer
                + ", '" + month + "-10 09:00:00', 2, 'visited', '" + month + "-09 00:00:00', '" + month + "-10 10:00:00', " + base + ")");
        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, coupon_discount,
                                          fee_base, fee_rate, fee_amount, coupon_cost, settlement_month)
                VALUES (?, ?, ?, ?, 0, ?, 0.04, ?, 0, ?)
                """, r, receiving, sender, base, base, fee, month);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
