package com.alleycrm.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** C22 사장님 내 가게: 영업 상태 · 예약 받기 · 영업시간 · 휴무일 변경 (FR-STO-02). */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class OwnerStoreApiTest {

    static final String PASSWORD = "password1";
    /** 2026-12-01(화) 09:00 KST */
    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");
    /** 2026-12-02(수) */
    static final String WEDNESDAY = "2026-12-02";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;
    @Autowired
    AppClock clock;
    @Autowired
    ApplicationEvents events;

    long regionId;
    long storeId;
    String ownerLogin;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('사장님 가게 테스트 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeId = store("approved");
        ownerLogin = "o" + System.nanoTime();
        accountService.createOwner(storeId, ownerLogin, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM account WHERE region_id = ? OR store_id IN (SELECT id FROM store WHERE region_id = ?)", regionId, regionId);
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void 사장님은_내_가게를_본다() throws Exception {
        mvc.perform(get("/api/v1/owner/store").session(login(ownerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(storeId)))
                .andExpect(jsonPath("$.businessStatus").value("open"))
                .andExpect(jsonPath("$.reservationEnabled").value(true))
                .andExpect(jsonPath("$.openTime").value("10:00"));
    }

    @Test
    void FR_STO_02_1_휴업으로_바꾸면_예약을_안_받고_바뀐_시각과_이벤트가_남는다() throws Exception {
        changeStatus(login(ownerLogin), Map.of("businessStatus", "paused", "reservationEnabled", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessStatus").value("paused"))
                .andExpect(jsonPath("$.reservable").value(false));

        // 쿠폰 30일 제한 해제(CouponLimitRepository)가 이 시각 전 다운로드를 뺀다. DB에는 UTC 벽시계로 들어간다.
        LocalDateTime changedAt = jdbc.queryForObject(
                "SELECT business_status_changed_at FROM store WHERE id = ?", LocalDateTime.class, storeId);
        assertThat(changedAt.toInstant(ZoneOffset.UTC)).isBetween(NOW, NOW.plusSeconds(60));
        // 미사용 쿠폰 적용 취소(E14)가 이 이벤트를 받는다.
        assertThat(events.stream(StoreBusinessStatusChangedEvent.class))
                .containsExactly(new StoreBusinessStatusChangedEvent(storeId, "paused"));
        // 추천 후보(CandidateRepository) · 예약 신청(AvailabilityStep)과 같은 기준
        slots().andExpect(jsonPath("$.acceptingReservations").value(false))
                .andExpect(jsonPath("$.slots[*].available").value(Matchers.everyItem(Matchers.is(false))));
    }

    @Test
    void 영업_상태가_그대로면_바뀐_시각과_이벤트를_남기지_않는다() throws Exception {
        changeStatus(login(ownerLogin), Map.of("businessStatus", "open", "reservationEnabled", true)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT business_status_changed_at FROM store WHERE id = ?", LocalDateTime.class, storeId))
                .isNull();
        assertThat(events.stream(StoreBusinessStatusChangedEvent.class)).isEmpty();
    }

    @Test
    void FR_STO_02_2_예약_받기를_멈추면_예약을_안_받고_영업_상태는_그대로다() throws Exception {
        changeStatus(login(ownerLogin), Map.of("businessStatus", "open", "reservationEnabled", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessStatus").value("open"))
                .andExpect(jsonPath("$.reservationEnabled").value(false))
                .andExpect(jsonPath("$.reservable").value(false));

        assertThat(events.stream(StoreBusinessStatusChangedEvent.class)).isEmpty();
        slots().andExpect(jsonPath("$.acceptingReservations").value(false));

        changeStatus(login(ownerLogin), Map.of("businessStatus", "open", "reservationEnabled", true))
                .andExpect(jsonPath("$.reservable").value(true));
        slots().andExpect(jsonPath("$.acceptingReservations").value(true));
    }

    @Test
    void FR_STO_02_4_영업시간_휴무일을_바꾸면_예약_가능_시간이_따른다() throws Exception {
        MockHttpSession owner = login(ownerLogin);
        hours(owner, Map.of("openTime", "12:00", "closeTime", "13:00", "closedDays", List.of("sun", "mon")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openTime").value("12:00"))
                .andExpect(jsonPath("$.closeTime").value("13:00"))
                .andExpect(jsonPath("$.closedDays").value(Matchers.contains("mon", "sun"))); // 요일 순서로 저장

        // 슬롯 30분(DB 기본값): 12:00 · 12:30 KST만
        slots().andExpect(jsonPath("$.slots[*].startAt").value(Matchers.contains("2026-12-02T03:00:00Z", "2026-12-02T03:30:00Z")));

        hours(owner, Map.of("openTime", "12:00", "closeTime", "13:00", "closedDays", List.of("wed")))
                .andExpect(status().isOk());
        slots().andExpect(jsonPath("$.slots").isEmpty());
        assertThat(jdbc.queryForObject("SELECT TIME_FORMAT(open_time, '%H:%i') FROM store WHERE id = ?", String.class, storeId))
                .isEqualTo("12:00");
    }

    @Test
    void 잘못된_값은_400() throws Exception {
        MockHttpSession owner = login(ownerLogin);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("businessStatus", "sleeping", "reservationEnabled", true),
                Map.of("businessStatus", "open"),
                Map.of("reservationEnabled", true))) {
            changeStatus(owner, bad).andExpect(status().isBadRequest());
        }
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("openTime", "13:00", "closeTime", "12:00"),
                Map.of("openTime", "12:00", "closeTime", "12:00"),
                Map.of("openTime", "12:00"),
                Map.of("openTime", "12:00", "closeTime", "13:00", "closedDays", List.of("mon", "mon")),
                Map.of("openTime", "12:00", "closeTime", "13:00", "closedDays", List.of("someday")))) {
            hours(owner, bad).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForMap("SELECT business_status, TIME_FORMAT(open_time, '%H:%i') AS open_hm FROM store WHERE id = ?", storeId))
                .containsEntry("business_status", "open").containsEntry("open_hm", "10:00");
    }

    @Test
    void 사장님이_아니면_403_승인_전_가게면_403_로그인_없으면_401() throws Exception {
        String customer = "c" + System.nanoTime();
        accountService.signupCustomer(customer, PASSWORD, "손님", null, null);
        String association = "a" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        String pendingOwner = "p" + System.nanoTime();
        accountService.createOwner(store("requested"), pendingOwner, PASSWORD);

        for (String loginId : List.of(customer, association, pendingOwner)) {
            MockHttpSession session = login(loginId);
            mvc.perform(get("/api/v1/owner/store").session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("forbidden_role"));
            changeStatus(session, Map.of("businessStatus", "paused", "reservationEnabled", true)).andExpect(status().isForbidden());
            hours(session, Map.of("openTime", "12:00", "closeTime", "13:00")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/owner/store")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/owner/store/status").with(TestCsrf.csrf(mvc)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessStatus\":\"paused\",\"reservationEnabled\":true}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT business_status FROM store WHERE id = ?", String.class, storeId)).isEqualTo("open");
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id = ?)", customer);
        jdbc.update("DELETE FROM account WHERE login_id = ?", customer);
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mvc.perform(put("/api/v1/owner/store/status").session(login(ownerLogin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessStatus\":\"paused\",\"reservationEnabled\":true}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT business_status FROM store WHERE id = ?", String.class, storeId)).isEqualTo("open");
    }

    private long store(String joinStatus) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, closed_days)
                VALUES (?, '사장님 가게', 'cafe', 'center', ?, '10:00', '22:00', '')
                """, regionId, joinStatus);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private ResultActions slots() throws Exception {
        return mvc.perform(get("/api/v1/stores/{id}/available-slots", storeId).param("date", WEDNESDAY))
                .andExpect(status().isOk());
    }

    private ResultActions changeStatus(MockHttpSession session, Map<String, Object> body) throws Exception {
        return send("/api/v1/owner/store/status", session, body);
    }

    private ResultActions hours(MockHttpSession session, Map<String, Object> body) throws Exception {
        return send("/api/v1/owner/store/hours", session, body);
    }

    private ResultActions send(String path, MockHttpSession session, Map<String, Object> body) throws Exception {
        return mvc.perform(put(path).session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new HashMap<>(body))));
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
