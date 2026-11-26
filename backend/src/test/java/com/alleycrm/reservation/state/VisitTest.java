package com.alleycrm.reservation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStatusChangedEvent;
import com.alleycrm.reservation.VisitProcessedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** D11 방문 처리(FR-RSV-09 #1 ~ #3)와 '방문 처리됨' 이벤트. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, VisitTest.Events.class})
class VisitTest {

    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    Events events;

    long storeA;
    long storeB;
    long customer;

    @TestConfiguration
    static class Events {
        final List<VisitProcessedEvent> visits = new ArrayList<>();
        final List<ReservationStatusChangedEvent> changes = new ArrayList<>();

        @EventListener
        void on(VisitProcessedEvent e) {
            visits.add(e);
        }

        @EventListener
        void on(ReservationStatusChangedEvent e) {
            changes.add(e);
        }
    }

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        events.visits.clear();
        events.changes.clear();
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'B', 'pub', 'center', 'approved')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerA', 'x', 'owner', ?)", storeA);
        long memberAccount = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('member1', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + memberAccount + ")");
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM coupon");
        jdbc.update("DELETE FROM coupon_policy");
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL");
        jdbc.update("DELETE FROM course_proposal");
        for (String t : new String[] {"reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_09_1_확정_예약을_방문_처리하면_방문_상태와_금액이_남고_이벤트가_나간다() throws Exception {
        long base = reservation(storeB, "confirmed", null);
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeA + ", NOW(6))");
        long r = reservation(storeA, "confirmed", proposal);

        visit(r, "{\"visitAmount\": 30000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visited"))
                .andExpect(jsonPath("$.visitAmount").value(30000))
                .andExpect(jsonPath("$.visitedAt").value(org.hamcrest.Matchers.startsWith("2026-11-28T10:00:00")))
                .andExpect(jsonPath("$.fromRecommendation").value(true))
                .andExpect(jsonPath("$.senderStoreId").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT visit_amount FROM reservation WHERE id = ?", Integer.class, r)).isEqualTo(30000);
        // 수수료(D15) · 소개 판정(E15)은 이 이벤트로 처리한다
        assertThat(events.visits).singleElement().satisfies(e -> {
            assertThat(e.reservationId()).isEqualTo(r);
            assertThat(e.visitAmount()).isEqualTo(30000);
            assertThat(e.courseProposalId()).isEqualTo(proposal);
            assertThat(e.senderStoreId()).isEqualTo(storeB);
        });
        assertThat(events.changes).singleElement().satisfies(e -> {
            assertThat(e.fromStatus()).isEqualTo("confirmed");
            assertThat(e.toStatus()).isEqualTo("visited");
        });
    }

    @Test
    void FR_RSV_09_2_결제_금액_없이_방문_처리하면_거절() throws Exception {
        long r = reservation(storeA, "confirmed", null);

        visit(r, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid"));
        visit(r, "{\"visitAmount\": -1}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, r)).isEqualTo("confirmed");
        assertThat(events.visits).isEmpty();
    }

    @Test
    void FR_RSV_09_3_쿠폰_적용_예약을_방문_처리해도_쿠폰은_적용됨_그대로() throws Exception {
        long r = reservation(storeA, "confirmed", null);
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count) VALUES ("
                + storeA + ", 'fixed_discount', 1000, 10, 1)");
        long coupon = insert("INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id) VALUES ("
                + customer + ", " + policy + ", " + storeA + ", NOW(6), NOW(6), 'applied', " + r + ")");

        visit(r, "{\"visitAmount\": 30000}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon)).isEqualTo("applied");
    }

    @Test
    void 확정이_아닌_예약은_방문_처리할_수_없다() throws Exception {
        long requested = reservation(storeA, "requested", null);
        long visited = reservation(storeA, "visited", null);

        visit(requested, "{\"visitAmount\": 10000}").andExpect(status().isConflict());
        visit(visited, "{\"visitAmount\": 10000}").andExpect(status().isConflict());
        assertThat(events.visits).isEmpty();
    }

    @Test
    void 다른_가게_예약은_404() throws Exception {
        long r = reservation(storeB, "confirmed", null);
        visit(r, "{\"visitAmount\": 10000}").andExpect(status().isNotFound());
    }

    private ResultActions visit(long id, String body) throws Exception {
        return mvc.perform(post("/api/v1/owner/reservations/" + id + "/visit")
                .with(TestCsrf.csrf(mvc)).with(user("ownerA"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long reservation(long store, String status, Long proposalId) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, course_proposal_id, sender_store_id) VALUES ('"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer + ", '2026-11-28 09:00:00', 2, '"
                + status + "', '2026-11-26 00:00:00', " + proposalId + ", " + (proposalId == null ? "NULL" : String.valueOf(storeB)) + ")");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
