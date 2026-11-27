package com.alleycrm.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.recommend.visitor.VisitorCookie;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** #307 방문자를 손님에 잇기: 로그인 · 예약 신청 때 잇고, 로그아웃 때 쿠키를 지운다 (CONTRACTS_API 3.3, FR-CRS-07 #2 · #3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VisitorLinkTest {

    static final String PASSWORD = "password1";
    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

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

    long regionId;
    long storeId;
    String member;
    long memberCustomerId;
    String owner;
    final List<String> anonIds = new java.util.ArrayList<>();

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('방문자 연결 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '방문자 연결 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        member = "vlmember" + System.nanoTime();
        long accountId = accountService.signupCustomer(member, PASSWORD, "회원", null, null).id();
        memberCustomerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        owner = "vlowner" + System.nanoTime();
        accountService.createOwner(storeId, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        List<Long> customers = jdbc.queryForList("SELECT DISTINCT customer_id FROM reservation WHERE store_id = ?", Long.class, storeId);
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM share_consent WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        for (String anonId : anonIds) {
            jdbc.update("DELETE FROM visitor WHERE anon_id = ?", anonId);
        }
        jdbc.update("DELETE FROM visitor WHERE customer_id = ?", memberCustomerId);
        for (long c : customers) {
            jdbc.update("DELETE FROM visitor WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ? AND type = 'guest'", c);
        }
        jdbc.update("DELETE FROM customer WHERE id = ?", memberCustomerId);
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'vl%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CRS_07_2_로그인하면_이_브라우저의_방문자가_회원_손님에_이어진다() throws Exception {
        String anonId = visitor();

        login(member, anonId);

        assertThat(linkedCustomer(anonId)).isEqualTo(memberCustomerId);
    }

    @Test
    void 사장님_로그인은_방문자를_잇지_않는다() throws Exception {
        String anonId = visitor();

        login(owner, anonId);

        assertThat(linkedCustomer(anonId)).isNull();
    }

    @Test
    void 비회원_예약_신청이_방문자를_그_손님에_잇는다() throws Exception {
        String anonId = visitor();

        String body = mvc.perform(post("/api/v1/reservations").with(csrfAndVisitor(anonId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeId", String.valueOf(storeId),
                                "startAt", "2026-11-28T09:00:00Z", "partySize", 2, "shareConsent", true,
                                "guest", Map.of("name", "비회원", "phone", "01066667777")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long reservationId = Long.parseLong(json.readTree(body).get("id").asString());
        long guestCustomer = jdbc.queryForObject("SELECT customer_id FROM reservation WHERE id = ?", Long.class, reservationId);
        assertThat(linkedCustomer(anonId)).isEqualTo(guestCustomer);
    }

    @Test
    void 예약_신청이_실패하면_연결도_남지_않는다() throws Exception {
        String anonId = visitor();

        // 영업시간 밖 → 단계 30에서 거절, 단계 20의 연결도 롤백
        mvc.perform(post("/api/v1/reservations").with(csrfAndVisitor(anonId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeId", String.valueOf(storeId),
                                "startAt", "2026-11-28T20:00:00Z", "partySize", 2, "shareConsent", true,
                                "guest", Map.of("name", "비회원", "phone", "01066668888")))))
                .andExpect(status().is4xxClientError());

        assertThat(linkedCustomer(anonId)).isNull();
    }

    @Test
    void 로그아웃하면_방문자_쿠키를_지운다() throws Exception {
        String anonId = visitor();
        MockHttpSession session = login(member, anonId);

        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/logout").session(session).with(csrfAndVisitor(anonId)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        Cookie cleared = response.getCookie(VisitorCookie.NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        // 이미 이은 기록은 그대로 둔다
        assertThat(linkedCustomer(anonId)).isEqualTo(memberCustomerId);
    }

    /** TestCsrf는 요청 쿠키를 CSRF 쿠키로 바꿔 놓으므로, 그 뒤에 방문자 쿠키를 더한다 */
    private RequestPostProcessor csrfAndVisitor(String anonId) throws Exception {
        RequestPostProcessor csrf = TestCsrf.csrf(mvc);
        return request -> {
            MockHttpServletRequest withCsrf = csrf.postProcessRequest(request);
            Cookie[] cookies = withCsrf.getCookies();
            Cookie[] all = java.util.Arrays.copyOf(cookies, cookies.length + 1);
            all[cookies.length] = new Cookie(VisitorCookie.NAME, anonId);
            withCsrf.setCookies(all);
            return withCsrf;
        };
    }

    /** 서버에 있는 익명 방문자 하나(탐색 기록이 있는 브라우저) */
    private String visitor() {
        String anonId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO visitor (anon_id) VALUES (?)", anonId);
        anonIds.add(anonId);
        return anonId;
    }

    private Long linkedCustomer(String anonId) {
        return jdbc.queryForObject("SELECT customer_id FROM visitor WHERE anon_id = ?", Long.class, anonId);
    }

    private MockHttpSession login(String loginId, String anonId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrfAndVisitor(anonId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
