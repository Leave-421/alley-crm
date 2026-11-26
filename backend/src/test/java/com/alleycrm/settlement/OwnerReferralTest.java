package com.alleycrm.settlement;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** D16 수수료 내역(FR-STL-03 #1) · 소개 실적(FR-STL-04 #1). 수수료 행은 D15가 만드는 모양 그대로 넣는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerReferralTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    long region;
    long storeA;
    long storeB;
    long storeC;
    long customer;

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = store("A");
        storeB = store("B");
        storeC = store("C");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerA', 'x', 'owner', ?)", storeA);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerB', 'x', 'owner', ?)", storeB);
        customer = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '김로코', '01012345678')");
        // A가 보내고 B가 받은 소개 2건(11월 · 12월), C가 받은 소개 1건(11월, A가 보냄)
        fee(storeB, storeA, "2026-11", 30000, 5000, 25000, 1000, 5000);
        fee(storeB, storeA, "2026-12", 20000, 0, 20000, 800, 0);
        fee(storeC, storeA, "2026-11", 10000, 0, 10000, 400, 0);
    }

    @AfterEach
    void clean() {
        for (String t : new String[] {"referral_fee", "settlement", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_STL_03_1_내_가게가_낸_수수료만_보낸_가게는_없다() throws Exception {
        mvc.perform(get("/api/v1/owner/referral-fees").param("month", "2026-11").with(user("ownerB")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalFee").value(1000))
                .andExpect(jsonPath("$.totalCouponCost").value(5000))
                .andExpect(jsonPath("$.settlementStatus").doesNotExist())
                .andExpect(jsonPath("$.referralFeeRate").value(0.04))
                .andExpect(jsonPath("$.items[0].maskedName").value("김*코"))
                .andExpect(jsonPath("$.items[0].feeBase").value(25000))
                .andExpect(jsonPath("$.items[0].couponDiscount").value(5000))
                .andExpect(jsonPath("$.items[0].senderStoreId").doesNotExist())
                .andExpect(jsonPath("$.items[0].senderStoreName").doesNotExist());
    }

    @Test
    void 월_정산이_있으면_상태를_보여준다() throws Exception {
        jdbc.update("INSERT INTO settlement (region_id, month, status) VALUES (?, '2026-11', 'confirmed')", region);
        mvc.perform(get("/api/v1/owner/referral-fees").param("month", "2026-11").with(user("ownerB")))
                .andExpect(jsonPath("$.settlementStatus").value("confirmed"));
    }

    @Test
    void FR_STL_04_1_보낸_손님과_받은_손님() throws Exception {
        mvc.perform(get("/api/v1/owner/referral-stats").with(user("ownerA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentCount").value(3))
                .andExpect(jsonPath("$.receivedCount").value(0));
        mvc.perform(get("/api/v1/owner/referral-stats").param("month", "2026-11").with(user("ownerB")))
                .andExpect(jsonPath("$.sentCount").value(0))
                .andExpect(jsonPath("$.receivedCount").value(1));
    }

    @Test
    void 잘못된_달은_400() throws Exception {
        mvc.perform(get("/api/v1/owner/referral-fees").param("month", "2026-13").with(user("ownerB")))
                .andExpect(status().isBadRequest());
    }

    private long store(String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '" + name + "', 'cafe', 'center', 'approved')");
    }

    private void fee(long receiving, long sender, String month, int visit, int discount, int base, int fee, int cost) {
        long r = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, visited_at, visit_amount)"
                + " VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + receiving + ", " + customer
                + ", '" + month + "-10 09:00:00', 2, 'visited', '" + month + "-09 00:00:00', '" + month + "-10 10:00:00', " + visit + ")");
        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, coupon_discount,
                                          fee_base, fee_rate, fee_amount, coupon_cost, settlement_month)
                VALUES (?, ?, ?, ?, ?, ?, 0.04, ?, ?, ?)
                """, r, receiving, sender, visit, discount, base, fee, cost, month);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
