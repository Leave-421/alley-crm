package com.alleycrm.reservation.owner;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** B8 요청: 사장님 목록 · 상세 · 승인 · 방문 처리 응답의 적용 쿠폰, 수수료율, 방문 처리 응답의 실제 쿠폰 할인액. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerReservationCouponTest {

    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long storeA;
    long storeB;
    long customer;
    long reservation;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'B', 'pub', 'center', 'approved')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerA', 'x', 'owner', ?)", storeA);
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, name, account_id) VALUES ('member', '김로코', " + acc + ")");
        // 서울 11-28 18:00 확정 예약 + 정액 5,000원 쿠폰 적용
        reservation = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)"
                + " VALUES ('RES00001', " + storeA + ", " + customer + ", '2026-11-28 09:00:00', 2, 'confirmed', '2026-11-26 00:00:00')");
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count, confirmed_at) VALUES ("
                + storeA + ", 'fixed_discount', 5000, 10, 1, NOW(6))");
        jdbc.update("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id)"
                + " VALUES (?, ?, ?, NOW(6), NOW(6) + INTERVAL 7 DAY, 'applied', ?)", customer, policy, storeA, reservation);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"coupon", "coupon_policy", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void 목록_항목에_적용_쿠폰과_수수료율이_있다() throws Exception {
        mvc.perform(get("/api/v1/owner/reservations").param("date", "2026-11-28").with(user("ownerA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referralFeeRate").value(0.04))
                .andExpect(jsonPath("$.items[0].coupon.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.items[0].coupon.discountAmount").value(5000))
                .andExpect(jsonPath("$.items[0].visitAmount").doesNotExist());
    }

    @Test
    void 상세는_내_가게_예약만_적용_쿠폰과_수수료율() throws Exception {
        mvc.perform(get("/api/v1/owner/reservations/" + reservation).with(user("ownerA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationNo").value("RES00001"))
                .andExpect(jsonPath("$.maskedName").value("김*코"))
                .andExpect(jsonPath("$.coupon.couponId").exists())
                .andExpect(jsonPath("$.referralFeeRate").value(0.04));

        long other = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)"
                + " VALUES ('RES00002', " + storeB + ", " + customer + ", '2026-11-28 09:00:00', 2, 'confirmed', '2026-11-26 00:00:00')");
        mvc.perform(get("/api/v1/owner/reservations/" + other).with(user("ownerA"))).andExpect(status().isNotFound());
    }

    @Test
    void 방문_처리_응답에_적용_쿠폰과_실제_할인액() throws Exception {
        mvc.perform(post("/api/v1/owner/reservations/" + reservation + "/visit").with(TestCsrf.csrf(mvc)).with(user("ownerA"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visitAmount\": 4000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visited"))
                .andExpect(jsonPath("$.visitAmount").value(4000))
                .andExpect(jsonPath("$.coupon.discountAmount").value(5000))
                // 정액 5,000원이지만 방문 금액 4,000원까지만 깎는다(FR-CPN-07)
                .andExpect(jsonPath("$.couponDiscountAmount").value(4000))
                .andExpect(jsonPath("$.startAt").value("2026-11-28T09:00:00Z"));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
