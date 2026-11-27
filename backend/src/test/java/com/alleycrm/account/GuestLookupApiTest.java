package com.alleycrm.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.notification.NotificationService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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

/** C13 비회원 예약 조회: 예약번호 + 전화번호 → 그 전화번호의 예약만 다루는 제한 세션 (FR-AUTH-03). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestLookupApiTest {

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
    @Autowired
    NotificationService notifications;
    @Autowired
    AccountService accountService;

    long regionId;
    long storeId;
    JsonNode first;
    JsonNode second;
    JsonNode othersReservation;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('비회원 조회 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '비회원 조회 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 1)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        first = reserve(PHONE, "2026-11-28T09:00:00Z", null);
        second = reserve(PHONE, "2026-11-29T09:00:00Z", null);
        othersReservation = reserve(OTHER_PHONE, "2026-11-28T10:00:00Z", null);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("""
                DELETE FROM notification WHERE recipient_type = 'guest_reservation'
                  AND recipient_id IN (SELECT id FROM reservation WHERE store_id = ?)""", storeId);
        jdbc.update("DELETE FROM share_consent WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        List<Long> customers = jdbc.queryForList("SELECT DISTINCT customer_id FROM reservation WHERE store_id = ?", Long.class, storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        for (long c : customers) {
            jdbc.update("DELETE FROM visitor WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'guestlookup%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'guestlookup%'");
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_AUTH_03_1_비회원_예약은_예약번호가_발급된다() {
        assertThat(first.get("reservationNo").asString()).matches("[A-HJ-NP-Z2-9]{8}");
    }

    @Test
    void FR_AUTH_03_2_예약번호와_전화번호가_맞으면_그_예약과_제한_세션을_받는다() throws Exception {
        MockHttpSession guest = lookup(no(first), PHONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asString()))
                .andExpect(jsonPath("$.status").value("requested"))
                // 손님 개인정보는 응답에 없다
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.guest").doesNotExist())
                .andReturn().getRequest().getSession(false) instanceof MockHttpSession s ? s : null;
        assertThat(guest).isNotNull();

        // 같은 전화번호의 예약만, 방문 일시가 늦은 것부터
        mvc.perform(get("/api/v1/guest/reservations").session(guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(
                        second.get("id").asString(), first.get("id").asString())));
    }

    @Test
    void FR_AUTH_03_2_제한_세션은_그_예약들의_알림만_보고_읽는다() throws Exception {
        notifications.notifyGuestReservation(id(first), "reservation_confirmed", Map.of("reservationId", first.get("id").asString()));
        notifications.notifyGuestReservation(id(othersReservation), "reservation_confirmed", Map.of());
        long others = jdbc.queryForObject(
                "SELECT id FROM notification WHERE recipient_type = 'guest_reservation' AND recipient_id = ?", Long.class, id(othersReservation));
        MockHttpSession guest = session(lookup(no(first), PHONE).andExpect(status().isOk()));

        String body = mvc.perform(get("/api/v1/notifications").session(guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("reservation_confirmed"))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String mine = json.readTree(body).get("items").get(0).get("id").asString();

        mvc.perform(post("/api/v1/notifications/{id}/read", mine).session(guest).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/{id}/read", others).session(guest).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications").session(guest)).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void FR_AUTH_03_3_예약번호와_전화번호가_다르면_401() throws Exception {
        lookup(no(first), OTHER_PHONE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
        lookup("ZZZZZZZZ", PHONE).andExpect(status().isUnauthorized());
    }

    @Test
    void 형식이_틀리면_400() throws Exception {
        lookup(no(first), "010-1234-5678").andExpect(status().isBadRequest());
        lookup("", PHONE).andExpect(status().isBadRequest());
    }

    @Test
    void 제한_세션은_허용한_경로만_쓰고_다른_로그인_API는_403() throws Exception {
        MockHttpSession guest = session(lookup(no(first), PHONE));

        mvc.perform(get("/api/v1/auth/me").session(guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(get("/api/v1/owner/reservation-settings").session(guest)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/wallets/me").session(guest)).andExpect(status().isForbidden());
    }

    @Test
    void 제한_세션에서_새_예약은_비회원_신청으로_처리된다() throws Exception {
        MockHttpSession guest = session(lookup(no(first), PHONE));

        mvc.perform(post("/api/v1/reservations").session(guest).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(PHONE, "2026-11-30T09:00:00Z"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/guest/reservations").session(guest)).andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void 비회원_예약_목록은_제한_세션만_로그인_없으면_401_계정이면_403() throws Exception {
        mvc.perform(get("/api/v1/guest/reservations")).andExpect(status().isUnauthorized());

        String login = "guestlookup" + System.nanoTime();
        accountService.signupCustomer(login, "password1", "회원", null, null);
        MockHttpSession member = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + login + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/v1/guest/reservations").session(member)).andExpect(status().isForbidden());
    }

    @Test
    void 로그아웃하면_제한_세션이_끝난다() throws Exception {
        MockHttpSession guest = session(lookup(no(first), PHONE));

        mvc.perform(post("/api/v1/auth/logout").session(guest).with(TestCsrf.csrf(mvc))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/guest/reservations").session(guest)).andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mvc.perform(post("/api/v1/guest/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reservationNo", no(first), "phone", PHONE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    private JsonNode reserve(String phone, String startAt, MockHttpSession session) throws Exception {
        var request = post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(phone, startAt)));
        if (session != null) {
            request.session(session);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> body(String phone, String startAt) {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(storeId));
        b.put("startAt", startAt);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("guest", Map.of("name", "비회원", "phone", phone));
        return b;
    }

    private ResultActions lookup(String reservationNo, String phone) throws Exception {
        return mvc.perform(post("/api/v1/guest/lookup").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reservationNo", reservationNo, "phone", phone))));
    }

    private static MockHttpSession session(ResultActions result) {
        return (MockHttpSession) result.andReturn().getRequest().getSession(false);
    }

    private static String no(JsonNode reservation) {
        return reservation.get("reservationNo").asString();
    }

    private static long id(JsonNode reservation) {
        return Long.parseLong(reservation.get("id").asString());
    }
}
