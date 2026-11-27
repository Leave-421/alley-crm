package com.alleycrm.reservation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 비회원 예약 취소 · 변경(G-13, D6 · D8)과 비회원 예약 목록의 취소 안내. 비회원 제한 세션(C13)에서만. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestCancelChangeTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    static final String PHONE = "01012345678";
    static final String OTHER_PHONE = "01099998888";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AppClock clock;

    long regionId;
    long storeId;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('비회원 취소 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot, deposit_per_person)
                VALUES (?, '비회원 취소 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 2, 5000)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM notification WHERE recipient_type = 'guest_reservation' AND recipient_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM share_consent WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM wallet_tx WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        List<Long> customers = jdbc.queryForList("SELECT DISTINCT customer_id FROM reservation WHERE store_id = ?", Long.class, storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        for (long c : customers) {
            jdbc.update("DELETE FROM visitor WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void 비회원_목록에_취소_안내가_있고_취소하면_예약금을_돌려받는다() throws Exception {
        JsonNode r = reserve(PHONE, "2026-11-29T09:00:00Z"); // 하루 전보다 앞 → 100% 환불
        MockHttpSession guest = lookup(r, PHONE);

        mvc.perform(get("/api/v1/guest/reservations").session(guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(r.get("id").asString()))
                .andExpect(jsonPath("$.items[0].reservationNo").value(r.get("reservationNo").asString()))
                .andExpect(jsonPath("$.items[0].cancelable").value(true))
                .andExpect(jsonPath("$.items[0].refundIfCanceled").value(10000))
                .andExpect(jsonPath("$.items[0].reservation").doesNotExist());

        mvc.perform(post("/api/v1/guest/reservations/{id}/cancel", id(r)).session(guest).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.cancelReason").value("customer_canceled"))
                .andExpect(jsonPath("$.refundAmount").value(10000));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_tx WHERE reservation_id = ? AND type = 'deposit_refunded' AND guest_phone = ?",
                Integer.class, id(r), PHONE)).isEqualTo(1);

        mvc.perform(get("/api/v1/guest/reservations").session(guest))
                .andExpect(jsonPath("$.items[0].cancelable").value(false))
                .andExpect(jsonPath("$.items[0].refundIfCanceled").doesNotExist());
    }

    @Test
    void 비회원_변경은_신청이_되고_바꿀_값이_대기한다() throws Exception {
        JsonNode r = reserve(PHONE, "2026-11-29T09:00:00Z");
        jdbc.update("UPDATE reservation SET status = 'confirmed' WHERE id = ?", id(r)); // 변경은 확정 예약만(D8)
        MockHttpSession guest = lookup(r, PHONE);

        mvc.perform(post("/api/v1/guest/reservations/{id}/change", id(r)).session(guest).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startAt\": \"2026-11-29T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.startAt").value("2026-11-29T09:00:00Z"))
                .andExpect(jsonPath("$.pendingStartAt").value("2026-11-29T10:00:00Z"));

        mvc.perform(post("/api/v1/guest/reservations/{id}/change", id(r)).session(guest).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 다른_전화번호의_예약은_404_로그인_없으면_401() throws Exception {
        JsonNode mine = reserve(PHONE, "2026-11-29T09:00:00Z");
        JsonNode others = reserve(OTHER_PHONE, "2026-11-29T10:00:00Z");
        MockHttpSession guest = lookup(mine, PHONE);

        mvc.perform(post("/api/v1/guest/reservations/{id}/cancel", id(others)).session(guest).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/guest/reservations/{id}/change", id(others)).session(guest).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\": 1}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/guest/reservations/{id}/cancel", id(mine)).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id(others))).isEqualTo("requested");
    }

    private JsonNode reserve(String phone, String startAt) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(storeId));
        b.put("startAt", startAt);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("guest", Map.of("name", "비회원", "phone", phone));
        return json.readTree(mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpSession lookup(JsonNode reservation, String phone) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/guest/lookup").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reservationNo", reservation.get("reservationNo").asString(), "phone", phone))))
                .andExpect(status().isOk());
        return (MockHttpSession) result.andReturn().getRequest().getSession(false);
    }

    private static long id(JsonNode reservation) {
        return Long.parseLong(reservation.get("id").asString());
    }
}
