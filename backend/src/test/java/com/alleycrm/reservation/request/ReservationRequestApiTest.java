package com.alleycrm.reservation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** D2 예약 신청: FR-RSV-01 인수 조건 #1 ~ #5(동의 기록 · 예약금 기록은 C8 · D10 단계가 생기면 더한다). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReservationRequestApiTest.FakeCustomerStep.class})
class ReservationRequestApiTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    /** 2026-11-28 18:00 Asia/Seoul */
    static final String START = "2026-11-28T09:00:00Z";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ObjectMapper json;
    @Autowired
    ReservationRequestService service;

    long storeId;

    /** 손님 식별(20, C4)이 생기기 전까지 테스트에서만 쓰는 대역: 비회원 손님을 만든다. */
    @TestConfiguration
    static class FakeCustomerStep {
        @Bean
        ReservationRequestStep fakeIdentifyCustomer(JdbcTemplate jdbc) {
            return new ReservationRequestStep() {
                public int order() {
                    return IDENTIFY_CUSTOMER;
                }

                public void apply(ReservationRequestContext ctx) {
                    jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', ?, ?)",
                            ctx.getBody().guest().name(), ctx.getBody().guest().phone());
                    ctx.setCustomerId(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
                }
            };
        }
    }

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('테스트 권역')");
        long regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (?, '테스트 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 1, 7)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM customer");
        jdbc.update("DELETE FROM store");
        jdbc.update("DELETE FROM region");
    }

    @Test
    void 단계는_order_순서로_등록된다() {
        assertThat(service.stepOrders()).containsExactly(10, 20, 30, 80);
    }

    @Test
    void FR_RSV_01_1_공유_동의가_없으면_거절() throws Exception {
        request(body(Map.of("shareConsent", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void FR_RSV_01_2_비회원_전화번호가_없으면_거절() throws Exception {
        request(body(Map.of("guest", Map.of("name", "손님"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void FR_RSV_01_3_슬롯이_다_차면_거절() throws Exception {
        request(body(Map.of())).andExpect(status().isCreated());

        request(body(Map.of("guest", Map.of("name", "다른 손님", "phone", "01099998888"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void FR_RSV_01_4_예약_가능_기간_밖이면_거절() throws Exception {
        // 오늘(11-27) + 7일 = 12-04까지. 12-05는 밖
        request(body(Map.of("startAt", "2026-12-05T09:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
        request(body(Map.of("startAt", "2026-12-04T09:00:00Z"))).andExpect(status().isCreated());
    }

    @Test
    void 지난_시각이면_거절() throws Exception {
        request(body(Map.of("startAt", "2026-11-27T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
    }

    @Test
    void 예약을_멈춘_가게면_거절() throws Exception {
        jdbc.update("UPDATE store SET reservation_enabled = FALSE WHERE id = ?", storeId);
        request(body(Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
    }

    @Test
    void 없는_가게나_승인_전_가게는_not_found() throws Exception {
        request(body(Map.of("storeId", "999999"))).andExpect(status().isNotFound());
        jdbc.update("UPDATE store SET join_status = 'requested' WHERE id = ?", storeId);
        request(body(Map.of())).andExpect(status().isNotFound());
    }

    @Test
    void FR_RSV_01_5_조건을_모두_만족하면_신청_상태로_저장() throws Exception {
        request(body(Map.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.requestedAt").value(org.hamcrest.Matchers.startsWith("2026-11-27T01:00:00")))
                .andExpect(jsonPath("$.startAt").value(START))
                .andExpect(jsonPath("$.reservationNo").value(org.hamcrest.Matchers.matchesPattern("[A-HJ-NP-Z2-9]{8}")))
                .andExpect(jsonPath("$.coupon").doesNotExist())
                .andExpect(jsonPath("$.fromRecommendation").value(false))
                // 응답에 손님 개인정보 없음
                .andExpect(jsonPath("$.guest").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 실패하면_아무것도_저장되지_않는다() throws Exception {
        request(body(Map.of())).andExpect(status().isCreated());
        int customersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class);

        // 손님 단계(20)는 지나고 자리 확인(30)에서 실패 → 손님 행도 남지 않음
        request(body(Map.of("guest", Map.of("name", "다른 손님", "phone", "01099998888"))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class)).isEqualTo(customersBefore);
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(Map.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    private ResultActions request(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/reservations").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private Map<String, Object> body(Map<String, Object> overrides) {
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("storeId", String.valueOf(storeId));
        b.put("startAt", START);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("guest", Map.of("name", "손님", "phone", "01012345678"));
        b.putAll(overrides);
        return b;
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
    }
}
