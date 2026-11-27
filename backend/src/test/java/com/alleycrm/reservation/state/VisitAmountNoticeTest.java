package com.alleycrm.reservation.state;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** D21 방문 금액 알림(FR-STL-07 #1 · #2, FR-NTF-03 #6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VisitAmountNoticeTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long store;
    long memberAccount;
    long member;
    long guest;

    @BeforeEach
    void seed() {
        clock.setTo(Instant.parse("2026-11-28T10:00:00Z"));
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '골목 카페', 'cafe', 'center', 'approved')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", store);
        memberAccount = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        member = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + memberAccount + ")");
        guest = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님', '01012345678')");
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"notification", "coupon", "coupon_policy", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_STL_07_1_회원은_방문_금액_알림과_내_예약에_같은_금액() throws Exception {
        long r = reservation(member);
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count, confirmed_at) VALUES ("
                + store + ", 'fixed_discount', 5000, 10, 1, NOW(6))");
        jdbc.update("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id)"
                + " VALUES (?, ?, ?, NOW(6), NOW(6) + INTERVAL 7 DAY, 'applied', ?)", member, policy, store, r);

        visit(r, 30000);

        String payload = jdbc.queryForObject("""
                SELECT payload FROM notification WHERE recipient_type = 'account' AND recipient_id = ? AND type = 'visit_amount_recorded'
                """, String.class, memberAccount);
        assertThat(payload).contains("\"visitAmount\": 30000").contains("\"couponDiscount\": 5000")
                .contains("\"amountAfterDiscount\": 25000").contains("골목 카페").doesNotContain("fee");

        mvc.perform(get("/api/v1/reservations/mine").with(user("m1")))
                .andExpect(jsonPath("$.items[0].reservations[0].visitAmount").value(30000))
                .andExpect(jsonPath("$.items[0].reservations[0].couponDiscount").value(5000))
                .andExpect(jsonPath("$.items[0].reservations[0].amountAfterDiscount").value(25000));
    }

    @Test
    void FR_STL_07_2_비회원은_방문_금액_알림이_없다() throws Exception {
        long r = reservation(guest);
        visit(r, 30000);
        // 다른 테스트가 남긴 알림과 섞이지 않게 이 예약의 알림만 센다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE type = 'visit_amount_recorded' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reservationId')) = ?
                """, Integer.class, String.valueOf(r))).isZero();
    }

    private void visit(long id, int amount) throws Exception {
        mvc.perform(post("/api/v1/owner/reservations/" + id + "/visit").with(TestCsrf.csrf(mvc)).with(user("owner1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visitAmount\": " + amount + "}"))
                .andExpect(status().isOk());
    }

    private long reservation(long customer) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at) VALUES ('"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer
                + ", '2026-11-28 09:00:00', 2, 'confirmed', '2026-11-25 00:00:00')");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
