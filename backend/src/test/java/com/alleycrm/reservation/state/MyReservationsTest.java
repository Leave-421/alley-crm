package com.alleycrm.reservation.state;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStateService;
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

/** D13 내 예약 코스 묶음(FR-RSV-12 #1 ~ #3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyReservationsTest {

    /** 2026-11-26 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-26T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ReservationStateService states;

    long storeA;
    long storeB;
    long storeC;
    long member;
    long other;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '골목 카페', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '골목 주점', 'pub', 'center', 'approved')");
        storeC = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '골목 공방', 'workshop', 'center', 'approved')");
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        member = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
        other = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '남', '01099999999')");
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("UPDATE reservation SET course_root_id = NULL");
        for (String t : new String[] {"reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_12_1_2_기준_예약이_취소_거절돼도_코스_묶음은_유지되고_기준은_취소됨() throws Exception {
        long base = reservation(member, storeA, "2026-11-28 09:00:00", "requested", null);
        long next = reservation(member, storeB, "2026-11-28 11:00:00", "requested", base);
        states.reject(storeA, base); // 사장님이 기준 예약 거절

        mvc.perform(get("/api/v1/reservations/mine").with(user("m1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseRootId").value(String.valueOf(base)))
                .andExpect(jsonPath("$.items[0].reservations[0].id").value(String.valueOf(base)))
                .andExpect(jsonPath("$.items[0].reservations[0].status").value("canceled"))
                .andExpect(jsonPath("$.items[0].reservations[0].cancelReason").value("store_rejected"))
                .andExpect(jsonPath("$.items[0].reservations[1].id").value(String.valueOf(next)))
                .andExpect(jsonPath("$.items[0].reservations[1].status").value("requested"))
                .andExpect(jsonPath("$.items[0].reservations[1].storeName").value("골목 주점"));
    }

    @Test
    void FR_RSV_12_3_같은_날_따로_한_일반_예약은_묶지_않는다() throws Exception {
        reservation(member, storeA, "2026-11-28 09:00:00", "confirmed", null);
        reservation(member, storeB, "2026-11-28 11:00:00", "confirmed", null);
        reservation(other, storeC, "2026-11-28 10:00:00", "confirmed", null); // 남의 예약은 안 보임

        mvc.perform(get("/api/v1/reservations/mine").with(user("m1")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].courseRootId").doesNotExist())
                .andExpect(jsonPath("$.items[0].reservations.length()").value(1))
                .andExpect(jsonPath("$.items[1].reservations[0].storeName").value("골목 주점"));
    }

    @Test
    void 취소할_때_환불_금액_안내() throws Exception {
        jdbc.update("UPDATE store SET cancel_deadline_hours = 3 WHERE id = ?", storeA);
        reservation(member, storeA, "2026-11-28 09:00:00", "confirmed", null); // 24시간 넘게 남음 → 100%
        reservation(member, storeA, "2026-11-26 05:00:00", "confirmed", null); // 4시간 남음, 마감(3시간 전) 전 → 50%
        reservation(member, storeA, "2026-11-26 02:00:00", "confirmed", null); // 1시간 남음 → 취소 불가

        mvc.perform(get("/api/v1/reservations/mine").with(user("m1")))
                .andExpect(jsonPath("$.items[0].reservations[0].cancelable").value(false))
                .andExpect(jsonPath("$.items[0].reservations[0].refundIfCanceled").doesNotExist())
                .andExpect(jsonPath("$.items[1].reservations[0].refundIfCanceled").value(5000))
                .andExpect(jsonPath("$.items[2].reservations[0].refundIfCanceled").value(10000))
                .andExpect(jsonPath("$.items[2].reservations[0].cancelable").value(true));
    }

    private long reservation(long customer, long store, String startUtc, String status, Long root) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, deposit_amount, course_root_id)"
                + " VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer + ", '" + startUtc
                + "', 2, '" + status + "', '2026-11-25 00:00:00', 10000, " + root + ")");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
