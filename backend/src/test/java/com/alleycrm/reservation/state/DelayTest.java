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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** D14 지연 입력(FR-RSV-10 #2). 리마인드(#1)는 C14 ReservationReminderJob이 맡는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DelayTest {

    /** 예약 시각 2026-11-28 10:00Z(19:00 서울) */
    static final String START = "2026-11-28 10:00:00";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long store;
    long ownerAccount;
    long member;
    long other;

    @BeforeEach
    void seed() {
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        ownerAccount = insert("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', " + store + ")");
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        member = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
        other = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '남', '01099999999')");
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"notification", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_10_2_지연을_입력하면_가게에_전달되고_예약_시각은_그대로() throws Exception {
        long r = reservation(member, "confirmed");
        clock.setTo(Instant.parse("2026-11-28T09:40:00Z"));

        delay(r, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedArrivalAt").value("2026-11-28T10:20:00Z"));

        assertThat(jdbc.queryForObject("SELECT start_at FROM reservation WHERE id = ?", String.class, r)).startsWith("2026-11-28 10:00:00");
        String payload = jdbc.queryForObject("""
                SELECT payload FROM notification WHERE recipient_type = 'account' AND recipient_id = ? AND type = 'delay_reported'
                """, String.class, ownerAccount);
        assertThat(payload).contains("2026-11-28T10:20:00Z").contains(String.valueOf(r));
    }

    @Test
    void 확정이_아니거나_남의_예약이거나_범위_밖이면_거절() throws Exception {
        clock.setTo(Instant.parse("2026-11-28T09:40:00Z"));
        delay(reservation(member, "requested"), 10).andExpect(status().isConflict());
        delay(reservation(other, "confirmed"), 10).andExpect(status().isNotFound());
        delay(reservation(member, "confirmed"), 0).andExpect(status().isBadRequest());
        delay(reservation(member, "confirmed"), 121).andExpect(status().isBadRequest());
        clock.setTo(Instant.parse("2026-11-28T12:30:00Z")); // 예약 2시간 반 뒤
        delay(reservation(member, "confirmed"), 10).andExpect(status().isConflict());
    }

    private ResultActions delay(long id, int minutes) throws Exception {
        return mvc.perform(post("/api/v1/reservations/" + id + "/delay").with(TestCsrf.csrf(mvc)).with(user("m1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delayMinutes\": " + minutes + "}"));
    }

    private long reservation(long customer, String status) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at) VALUES ('"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer + ", '" + START + "', 2, '" + status
                + "', '2026-11-25 00:00:00')");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
