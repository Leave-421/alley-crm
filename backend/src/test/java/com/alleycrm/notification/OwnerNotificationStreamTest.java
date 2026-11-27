package com.alleycrm.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** C15 사장님 실시간 알림(SSE): 세션 쿠키 인증, 커밋 뒤 전달, 다른 가게 사장님에게는 없음 (FR-NTF-01, NFR-05). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerNotificationStreamTest {

    static final String PASSWORD = "password1";
    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    static final String STREAM = "/api/v1/owner/notifications/stream";

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
    NotificationService notifications;
    @Autowired
    TransactionTemplate tx;

    long regionId;
    long storeA;
    long storeB;
    String ownerA;
    String ownerB;
    long ownerAId;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('실시간 알림 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("실시간 가게 A");
        storeB = store("실시간 가게 B");
        ownerA = "sseowner" + System.nanoTime();
        ownerAId = accountService.createOwner(storeA, ownerA, PASSWORD).id();
        ownerB = "sseowner" + System.nanoTime();
        accountService.createOwner(storeB, ownerB, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM notification");
        List<Long> customers = jdbc.queryForList(
                "SELECT DISTINCT customer_id FROM reservation WHERE store_id IN (?, ?)", Long.class, storeA, storeB);
        jdbc.update("DELETE FROM share_consent WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id IN (?, ?))",
                storeA, storeB);
        jdbc.update("DELETE FROM reservation WHERE store_id IN (?, ?)", storeA, storeB);
        for (long c : customers) {
            jdbc.update("DELETE FROM visitor WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'sse%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sse%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_NTF_01_1_NFR_05_1_연결해_둔_사장님에게_새_예약이_새로_고침_없이_온다() throws Exception {
        MvcResult a = open(login(ownerA));

        String reservationId = reserve(storeA);

        String body = a.getResponse().getContentAsString();
        assertThat(body).contains("event:notification").contains("\"type\":\"reservation_requested\"")
                .contains("\"reservationId\":\"" + reservationId + "\"");
        // 보낸 알림은 알림함(GET /notifications)의 항목과 같다
        long stored = jdbc.queryForObject("SELECT id FROM notification WHERE recipient_id = ? AND type = 'reservation_requested'",
                Long.class, ownerAId);
        assertThat(body).contains("id:" + stored).contains("\"id\":\"" + stored + "\"");
    }

    @Test
    void NFR_05_3_다른_가게_새_예약은_전달되지_않는다() throws Exception {
        MvcResult a = open(login(ownerA));
        MvcResult b = open(login(ownerB));

        reserve(storeB);

        assertThat(a.getResponse().getContentAsString()).doesNotContain("event:notification");
        assertThat(b.getResponse().getContentAsString()).contains("event:notification");
    }

    @Test
    void 롤백된_알림은_보내지_않는다() throws Exception {
        MvcResult a = open(login(ownerA));

        tx.executeWithoutResult(s -> {
            notifications.notifyAccount(ownerAId, NotificationType.RESERVATION_REQUESTED, Map.of("reservationId", "1"));
            s.setRollbackOnly();
        });

        assertThat(a.getResponse().getContentAsString()).doesNotContain("event:notification");
    }

    @Test
    void NFR_05_2_세션_없으면_401_사장님이_아니면_403() throws Exception {
        mvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());

        String customer = "ssecustomer" + System.nanoTime();
        accountService.signupCustomer(customer, PASSWORD, "회원", null, null);
        mvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).session(login(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    private MvcResult open(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).session(session))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return result;
    }

    /** 비회원 예약 신청(D2) → 커밋 뒤 사장님 알림 */
    private String reserve(long storeId) throws Exception {
        String body = mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeId", String.valueOf(storeId),
                                "startAt", "2026-11-28T09:00:00Z", "partySize", 2, "shareConsent", true,
                                "guest", Map.of("name", "비회원", "phone", "01077778888")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private long store(String name) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, 'cafe', 'center', 'approved', '10:00', '22:00', 1)
                """, regionId, name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
