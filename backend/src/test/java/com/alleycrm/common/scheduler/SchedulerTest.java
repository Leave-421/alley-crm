package com.alleycrm.common.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

/** D7 자동 작업(NFR-06 #1): 사람이 누르지 않아도 자동 거절 · 자동 취소 · 쿠폰 만료가 처리된다. */
@SpringBootTest(properties = "app.demo-tools.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SchedulerTest {

    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    ScheduledJobRunner runner;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    MockMvc mvc;

    long store;
    long customer;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        customer = insert("INSERT INTO customer (type) VALUES ('member')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role) VALUES ('anyone', 'x', 'customer')");
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"coupon", "coupon_policy", "reservation", "account", "customer", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void 자동_작업이_모두_등록돼_있다() {
        assertThat(runner.jobNames()).contains("auto_reject", "auto_cancel", "coupon_expiry", "fund_budget_release");
    }

    @Test
    void NFR_06_1_시간이_지나면_자동_거절_자동_취소_쿠폰_만료가_처리된다() {
        long dueRequest = reservation("requested", "2026-11-27 00:00:00");   // 예약 시각 지남 → 자동 거절
        long staleConfirmed = reservation("confirmed", "2026-11-24 09:00:00"); // 예약일 다음 날 끝남 → 자동 취소
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, confirmed_at) VALUES ("
                + store + ", 'fixed_discount', 1000, 10, NOW(6))");
        long coupon = insert("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status) VALUES ("
                + customer + ", " + policy + ", " + store + ", '2026-11-10 00:00:00', '2026-11-17 00:00:00', 'downloaded')");

        Map<String, Integer> ran = runner.runAll();

        assertThat(ran).containsEntry("auto_reject", 1).containsEntry("auto_cancel", 1);
        assertThat(statusOf(dueRequest)).isEqualTo("canceled");
        assertThat(statusOf(staleConfirmed)).isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon)).isEqualTo("expired");
        // 다시 돌려도 바뀌는 것 없음
        assertThat(runner.runAll()).containsEntry("auto_reject", 0).containsEntry("auto_cancel", 0);
    }

    @Test
    void 시연_시계를_앞당기면_자동_작업이_바로_돈다() throws Exception {
        long r = reservation("requested", "2026-11-27 05:00:00");
        mvc.perform(post("/api/v1/demo/clock").with(TestCsrf.csrf(mvc)).with(user("anyone"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"advanceMinutes\": 300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.auto_reject").value(1));
        assertThat(statusOf(r)).isEqualTo("canceled");
    }

    private long reservation(String status, String startUtc) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at) VALUES ('"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer + ", '" + startUtc
                + "', 2, '" + status + "', '2026-11-20 00:00:00')");
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
