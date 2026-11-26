package com.alleycrm.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.request.ReservationRequestedEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** C14 알림 저장 · 목록 · 읽음, 새 예약 → 사장님 알림 (FR-NTF-02 · FR-NTF-01 알림함). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiTest {

    static final String PASSWORD = "password1";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    NotificationService notifications;
    @Autowired
    AccountService accountService;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    TransactionTemplate tx;

    String customerLogin;
    long customerId;
    String otherLogin;
    long otherId;

    @BeforeEach
    void accounts() {
        customerLogin = "n" + System.nanoTime();
        customerId = accountService.signupCustomer(customerLogin, PASSWORD, "회원", null, null).id();
        otherLogin = "n" + System.nanoTime();
        otherId = accountService.signupCustomer(otherLogin, PASSWORD, "다른 회원", null, null).id();
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM notification");
    }

    @Test
    void 내_알림을_최근_것부터_안_읽은_수와_함께_본다() throws Exception {
        clock.setTo(Instant.parse("2026-11-27T01:00:00Z"));
        notifications.notifyAccount(customerId, NotificationType.RESERVATION_CONFIRMED, Map.of("reservationId", "10"));
        clock.setTo(Instant.parse("2026-11-27T02:00:00Z"));
        notifications.notifyAccount(customerId, NotificationType.RESERVATION_CANCELED, Map.of("reservationId", "11"));
        notifications.notifyAccount(otherId, NotificationType.REMINDER, Map.of("reservationId", "12"));

        mvc.perform(get("/api/v1/notifications").session(login(customerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.items[0].type").value("reservation_canceled"))
                .andExpect(jsonPath("$.items[0].payload.reservationId").value("11"))
                .andExpect(jsonPath("$.items[0].createdAt").value(startsWith("2026-11-27T02:00:00")))
                .andExpect(jsonPath("$.items[0].readAt").isEmpty())
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[1].type").value("reservation_confirmed"));
    }

    @Test
    void 읽음_처리하면_안_읽은_수가_줄고_다시_읽어도_처음_시각을_유지한다() throws Exception {
        clock.setTo(Instant.parse("2026-11-27T01:00:00Z"));
        notifications.notifyAccount(customerId, NotificationType.RESERVATION_CONFIRMED, Map.of());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
        MockHttpSession session = login(customerLogin);

        clock.setTo(Instant.parse("2026-11-27T03:00:00Z"));
        read(id, session).andExpect(status().isNoContent());
        clock.setTo(Instant.parse("2026-11-27T05:00:00Z"));
        read(id, session).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/notifications").session(session))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items[0].readAt").value(startsWith("2026-11-27T03:00:00")));
    }

    @Test
    void 남의_알림은_읽음_처리할_수_없고_404() throws Exception {
        notifications.notifyAccount(otherId, NotificationType.REMINDER, Map.of());
        long othersId = jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);

        read(othersId, login(customerLogin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notification WHERE id = ?", Boolean.class, othersId)).isTrue();
    }

    @Test
    void 로그인_안_하면_401_잘못된_페이지는_400() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications").param("size", "101").session(login(customerLogin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 새_예약이_커밋되면_그_가게_사장님에게_알림이_생긴다() throws Exception {
        long storeId = storeWithOwner();
        String ownerLogin = jdbc.queryForObject("SELECT login_id FROM account WHERE store_id = ?", String.class, storeId);
        try {
            tx.executeWithoutResult(s -> events.publishEvent(new ReservationRequestedEvent(77L, storeId, 1L)));

            mvc.perform(get("/api/v1/notifications").session(login(ownerLogin)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].type").value("reservation_requested"))
                    .andExpect(jsonPath("$.items[0].payload.reservationId").value("77"))
                    // 손님 개인정보는 넣지 않는다
                    .andExpect(jsonPath("$.items[0].payload.name").doesNotExist())
                    .andExpect(jsonPath("$.items[0].payload.phone").doesNotExist());
        } finally {
            cleanStore(storeId);
        }
    }

    @Test
    void 롤백된_예약에는_알림이_없다() {
        long storeId = storeWithOwner();
        try {
            tx.executeWithoutResult(s -> {
                events.publishEvent(new ReservationRequestedEvent(78L, storeId, 1L));
                s.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isZero();
        } finally {
            cleanStore(storeId);
        }
    }

    /** 사장님 계정 생성은 C5 범위라 테스트에서는 직접 넣는다 */
    private long storeWithOwner() {
        jdbc.update("INSERT INTO region (name) VALUES ('알림 테스트 권역')");
        long regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (?, '알림 가게', 'cafe', 'center', 'approved')", regionId);
        long storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES (?, ?, 'owner', ?)",
                "o" + System.nanoTime(), passwordEncoder.encode(PASSWORD), storeId);
        return storeId;
    }

    private void cleanStore(long storeId) {
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        jdbc.update("DELETE FROM account WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    private org.springframework.test.web.servlet.ResultActions read(long id, MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/v1/notifications/{id}/read", id).session(session).with(TestCsrf.csrf(mvc)));
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
