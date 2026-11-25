package com.alleycrm.reservation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStateService;
import com.alleycrm.reservation.ReservationStatus;
import com.alleycrm.reservation.ReservationStatusChangedEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** D3 승인 · 거절 · 자동 거절(FR-RSV-03), D4 자동 취소 · 상태 값 · 날짜별 목록(FR-RSV-02 · 04). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReservationStateTest.Events.class})
class ReservationStateTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ReservationStateService states;
    @Autowired
    Events events;

    long storeA;
    long storeB;
    long customer;

    @TestConfiguration
    static class Events {
        final List<ReservationStatusChangedEvent> received = new ArrayList<>();

        @EventListener
        void on(ReservationStatusChangedEvent e) {
            received.add(e);
        }
    }

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        events.received.clear();
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'B', 'pub', 'center', 'approved')");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('ownerA', 'x', 'owner', ?)", storeA);
        jdbc.update("INSERT INTO account (login_id, password_hash, role) VALUES ('member1', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '김로코', '01012345678')");
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL");
        jdbc.update("DELETE FROM course_proposal");
        for (String t : new String[] {"reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---- D3 FR-RSV-03

    @Test
    void FR_RSV_03_1_승인하면_확정() throws Exception {
        long r = reservation(storeA, "2026-11-28T09:00:00", "requested", null);

        owner(post("/api/v1/owner/reservations/" + r + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        assertThat(statusOf(r)).isEqualTo("confirmed");
        assertThat(events.received).singleElement()
                .satisfies(e -> assertThat(e.toStatus()).isEqualTo("confirmed"));
    }

    @Test
    void FR_RSV_03_2_거절하면_취소_가게_거절_이벤트로_환불_쿠폰_처리() throws Exception {
        long r = reservation(storeA, "2026-11-28T09:00:00", "requested", null);

        owner(post("/api/v1/owner/reservations/" + r + "/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.cancelReason").value("store_rejected"));
        // 환불(D10) · 쿠폰 적용 취소(E)는 이 이벤트를 받아 처리한다
        assertThat(events.received).singleElement().satisfies(e -> {
            assertThat(e.cancelReason()).isEqualTo("store_rejected");
            assertThat(e.byScheduler()).isFalse();
        });
    }

    @Test
    void FR_RSV_03_3_예약_시각까지_처리가_없으면_자동_거절() {
        long due = reservation(storeA, "2026-11-27T01:00:00", "requested", null);
        long later = reservation(storeA, "2026-11-27T02:00:00", "requested", null);

        assertThat(states.autoRejectDue()).isEqualTo(1);

        assertThat(statusOf(due)).isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM reservation WHERE id = ?", String.class, due))
                .isEqualTo("store_rejected");
        assertThat(statusOf(later)).isEqualTo("requested");
        assertThat(events.received).singleElement().satisfies(e -> assertThat(e.byScheduler()).isTrue());
    }

    @Test
    void 이미_처리한_예약은_다시_승인_거절할_수_없다() throws Exception {
        long r = reservation(storeA, "2026-11-28T09:00:00", "confirmed", null);
        owner(post("/api/v1/owner/reservations/" + r + "/reject"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 다른_가게_예약은_404() throws Exception {
        long r = reservation(storeB, "2026-11-28T09:00:00", "requested", null);
        owner(post("/api/v1/owner/reservations/" + r + "/confirm")).andExpect(status().isNotFound());
        assertThat(statusOf(r)).isEqualTo("requested");
    }

    @Test
    void 사장님이_아니면_403() throws Exception {
        long r = reservation(storeA, "2026-11-28T09:00:00", "requested", null);
        mvc.perform(post("/api/v1/owner/reservations/" + r + "/confirm").with(csrf()).with(user("member1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    // ---- D4 FR-RSV-02

    @Test
    void FR_RSV_02_1_예약일_다음_날까지_아무것도_없으면_자동_취소() {
        // 예약일 11-25(서울) → 다음 날 11-26이 끝났으므로 11-27에는 대상
        long old = reservation(storeA, "2026-11-25T09:00:00", "confirmed", null);
        // 예약일 11-26 → 다음 날 11-27이 아직 안 끝남
        long yesterday = reservation(storeA, "2026-11-26T09:00:00", "confirmed", null);

        assertThat(states.autoCancelDue()).isEqualTo(1);

        assertThat(statusOf(old)).isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM reservation WHERE id = ?", String.class, old))
                .isEqualTo("auto_canceled");
        assertThat(statusOf(yesterday)).isEqualTo("confirmed");
        // 환불 없음 · 쿠폰 적용 취소는 사유 auto_canceled로 받는 쪽이 정한다
        assertThat(events.received).singleElement().satisfies(e -> assertThat(e.cancelReason()).isEqualTo("auto_canceled"));
    }

    @Test
    void FR_RSV_02_2_방문_처리된_예약은_자동_작업이_바꾸지_않는다() {
        long visited = reservation(storeA, "2026-11-20T09:00:00", "visited", null);

        assertThat(states.autoCancelDue()).isZero();
        assertThat(states.autoRejectDue()).isZero();
        assertThat(statusOf(visited)).isEqualTo("visited");
    }

    @Test
    void FR_RSV_02_3_예약_상태는_4가지뿐_노쇼_없음() {
        List<String> values = Arrays.stream(ReservationStatus.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(ReservationStateTest::value)
                .toList();
        assertThat(values).containsExactlyInAnyOrder("requested", "confirmed", "visited", "canceled");
    }

    // ---- D4 FR-RSV-04

    @Test
    void FR_RSV_04_1_날짜별_목록은_내_가게만_추천_여부_포함_보낸_가게_없음() throws Exception {
        long other = reservation(storeB, "2026-11-28T09:00:00", "requested", null);
        long proposal = proposal(other, storeA);
        reservation(storeA, "2026-11-28T10:00:00", "requested", proposal);
        reservation(storeA, "2026-11-28T03:00:00", "confirmed", null);
        // 11-29 00:30 서울 = 11-28 15:30 UTC → 11-29 목록
        reservation(storeA, "2026-11-28T15:30:00", "requested", null);

        owner(get("/api/v1/owner/reservations").param("date", "2026-11-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].startAt").value("2026-11-28T03:00:00Z"))
                .andExpect(jsonPath("$.items[0].fromRecommendation").value(false))
                .andExpect(jsonPath("$.items[1].fromRecommendation").value(true))
                .andExpect(jsonPath("$.items[1].maskedName").value("김*코"))
                .andExpect(jsonPath("$.items[1].senderStoreId").doesNotExist())
                .andExpect(jsonPath("$.items[1].phone").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions owner(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(csrf()).with(user("ownerA")));
    }

    private long reservation(long store, String startUtc, String status, Long proposalId) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, course_proposal_id, sender_store_id) VALUES ('"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + customer + ", '" + startUtc + "', 2, '"
                + status + "', '2026-11-26 00:00:00', " + proposalId + ", " + (proposalId == null ? "NULL" : String.valueOf(storeB)) + ")");
    }

    private long proposal(long baseReservation, long recommended) {
        return insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + baseReservation + ", " + recommended + ", NOW(6))");
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String value(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
