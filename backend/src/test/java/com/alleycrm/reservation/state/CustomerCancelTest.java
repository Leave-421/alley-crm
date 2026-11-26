package com.alleycrm.reservation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.test.web.servlet.ResultActions;

/** D6 손님 취소 · 환불(FR-RSV-06 #1 ~ #5). 예약금 10,000원을 낸 회원 예약. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerCancelTest {

    /** 예약 시각 2026-11-28 09:00Z */
    static final Instant START = Instant.parse("2026-11-28T09:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long store;
    long member;
    long other;

    @BeforeEach
    void seed() {
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        // 가게 취소 마감: 예약 3시간 전, 환불 비율은 가게가 정하지 않음(권역 기본 0.5)
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status, cancel_deadline_hours) VALUES ("
                + region + ", 'A', 'cafe', 'center', 'approved', 3)");
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        member = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
        long acc2 = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m2', 'x', 'customer')");
        other = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc2 + ")");
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"wallet_tx", "wallet", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_06_1_25시간_전이면_100퍼센트_환불() throws Exception {
        long r = paidReservation(member, "confirmed");
        clock.setTo(START.minusSeconds(25 * 3600));

        cancel("m1", r).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.cancelReason").value("customer_canceled"))
                .andExpect(jsonPath("$.refundAmount").value(10000));
        assertThat(balance(member)).isEqualTo(10000);
    }

    @Test
    void FR_RSV_06_2_24시간_안_마감_전이면_가게_비율_없으면_권역_기본값() throws Exception {
        long r = paidReservation(member, "requested");
        clock.setTo(START.minusSeconds(10 * 3600));

        cancel("m1", r).andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(5000));
        assertThat(balance(member)).isEqualTo(5000);

        // 가게가 30%로 정하면 그 값
        jdbc.update("UPDATE store SET late_cancel_refund_rate = 0.3 WHERE id = ?", store);
        long r2 = paidReservation(member, "confirmed");
        cancel("m1", r2).andExpect(jsonPath("$.refundAmount").value(3000));
    }

    @Test
    void FR_RSV_06_3_가게_취소_마감이_지나면_거절() throws Exception {
        long r = paidReservation(member, "confirmed");
        clock.setTo(START.minusSeconds(2 * 3600));

        cancel("m1", r).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, r)).isEqualTo("confirmed");
    }

    @Test
    void FR_RSV_06_4_마감을_24시간으로_둔_가게도_24시간_전까지는_100퍼센트() throws Exception {
        jdbc.update("UPDATE store SET cancel_deadline_hours = 24 WHERE id = ?", store);
        long r = paidReservation(member, "confirmed");
        clock.setTo(START.minusSeconds(25 * 3600));
        cancel("m1", r).andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(10000));

        // 24시간 안이면 그 가게는 이미 마감
        long r2 = paidReservation(member, "confirmed");
        clock.setTo(START.minusSeconds(23 * 3600));
        cancel("m1", r2).andExpect(status().isConflict());
    }

    @Test
    void FR_RSV_06_5_다른_손님_예약은_404() throws Exception {
        long r = paidReservation(other, "confirmed");
        clock.setTo(START.minusSeconds(25 * 3600));
        cancel("m1", r).andExpect(status().isNotFound());
    }

    @Test
    void 이미_취소_방문한_예약은_취소할_수_없다() throws Exception {
        long r = paidReservation(member, "visited");
        clock.setTo(START.minusSeconds(25 * 3600));
        cancel("m1", r).andExpect(status().isConflict());
    }

    private long paidReservation(long customer, String status) {
        long r = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, deposit_amount)"
                + " VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer
                + ", '2026-11-28 09:00:00', 2, '" + status + "', '2026-11-20 00:00:00', 10000)");
        jdbc.update("INSERT IGNORE INTO wallet (owner_type, owner_id, balance) VALUES ('customer', ?, 0)", customer);
        long wallet = jdbc.queryForObject("SELECT id FROM wallet WHERE owner_type = 'customer' AND owner_id = ?", Long.class, customer);
        jdbc.update("INSERT INTO wallet_tx (wallet_id, type, amount, reservation_id, created_at) VALUES (?, 'deposit_paid', -10000, ?, NOW(6))", wallet, r);
        return r;
    }

    private ResultActions cancel(String login, long id) throws Exception {
        return mvc.perform(post("/api/v1/reservations/" + id + "/cancel").with(TestCsrf.csrf(mvc)).with(user(login)));
    }

    private long balance(long customer) {
        return jdbc.queryForObject("SELECT balance FROM wallet WHERE owner_type = 'customer' AND owner_id = ?", Long.class, customer);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
