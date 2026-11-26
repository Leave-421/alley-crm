package com.alleycrm.reservation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** C4 손님 식별: 예약 신청 단계 20 (FR-CUS-02, FR-AUTH-02 #1). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdentifyCustomerStepTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    /** 2026-11-28 18:00 Asia/Seoul */
    static final String START = "2026-11-28T09:00:00Z";
    static final String PASSWORD = "password1";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;
    @Autowired
    PasswordEncoder passwordEncoder;

    long regionId;
    long storeA;
    long storeB;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('식별 테스트 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("가게 A");
        storeB = store("가게 B");
    }

    private long store(String name) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (?, ?, 'cafe', 'center', 'approved', '10:00', '22:00', 5, 7)
                """, regionId, name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM share_consent");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM customer");
        jdbc.update("DELETE FROM account WHERE store_id IN (?, ?) OR region_id = ?", storeA, storeB, regionId);
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CUS_02_1_같은_전화번호의_비회원_예약은_가게가_달라도_한_손님() throws Exception {
        request(guestBody(storeA, "손님", "01012345678"), null).andExpect(status().isCreated());
        request(guestBody(storeB, "손님", "01012345678"), null).andExpect(status().isCreated());

        List<Long> customerIds = jdbc.queryForList(
                "SELECT customer_id FROM reservation WHERE store_id IN (?, ?)", Long.class, storeA, storeB);
        assertThat(customerIds).hasSize(2).containsOnly(customerIds.get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE type = 'guest'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 전화번호가_다르면_다른_손님() throws Exception {
        request(guestBody(storeA, "손님", "01012345678"), null).andExpect(status().isCreated());
        request(guestBody(storeB, "손님", "01087654321"), null).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT customer_id) FROM reservation", Integer.class)).isEqualTo(2);
    }

    @Test
    void 같은_전화번호로_다시_예약하면_이름은_최신_값_성별_연령대는_보낸_값만_바뀐다() throws Exception {
        request(guestBody(storeA, "김손님", "01012345678", "female", "age_30s"), null).andExpect(status().isCreated());
        request(guestBody(storeB, "김새이름", "01012345678", null, "age_40s"), null).andExpect(status().isCreated());

        assertThat(jdbc.queryForMap("SELECT name, gender, age_group FROM customer WHERE phone = '01012345678'"))
                .containsEntry("name", "김새이름").containsEntry("gender", "female").containsEntry("age_group", "age_40s");
    }

    @Test
    void 파기된_비회원의_전화번호로는_이어지지_않는다() throws Exception {
        request(guestBody(storeA, "손님", "01012345678"), null).andExpect(status().isCreated());
        jdbc.update("UPDATE customer SET purged_at = NOW(6) WHERE phone = '01012345678'");

        request(guestBody(storeB, "손님", "01012345678"), null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT customer_id) FROM reservation", Integer.class)).isEqualTo(2);
    }

    @Test
    void FR_AUTH_02_1_회원_예약은_회원정보의_성별_연령대를_가진_손님으로_저장된다() throws Exception {
        String loginId = "m" + System.nanoTime();
        accountService.signupCustomer(loginId, PASSWORD, "회원", "male", "age_20_or_under");
        MockHttpSession session = login(loginId);

        // 회원이 보낸 guest는 무시한다(계약)
        request(guestBody(storeA, "다른 이름", "01099990000"), session).andExpect(status().isCreated());

        assertThat(jdbc.queryForMap("""
                SELECT c.type, c.gender, c.age_group
                FROM reservation r JOIN customer c ON c.id = r.customer_id
                JOIN account a ON a.id = c.account_id WHERE a.login_id = ?""", loginId))
                .containsEntry("type", "member").containsEntry("gender", "male").containsEntry("age_group", "age_20_or_under");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE type = 'guest'", Integer.class)).isZero();
    }

    @Test
    void 사장님_계정은_예약을_신청할_수_없다() throws Exception {
        String loginId = "o" + System.nanoTime();
        // 사장님 계정 생성은 C5 범위라 테스트에서는 직접 넣는다
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES (?, ?, 'owner', ?)",
                loginId, passwordEncoder.encode(PASSWORD), storeA);

        request(guestBody(storeB, "손님", "01012345678"), login(loginId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isZero();
    }

    @Test
    void 상인회_계정은_예약을_신청할_수_없다() throws Exception {
        String loginId = "a" + System.nanoTime();
        accountService.createAssociation(regionId, loginId, PASSWORD);

        request(guestBody(storeA, "손님", "01012345678"), login(loginId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private ResultActions request(Map<String, Object> body, MockHttpSession session) throws Exception {
        var builder = post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (session != null) {
            builder.session(session);
        }
        return mvc.perform(builder);
    }

    private Map<String, Object> guestBody(long storeId, String name, String phone) {
        return guestBody(storeId, name, phone, null, null);
    }

    private Map<String, Object> guestBody(long storeId, String name, String phone, String gender, String ageGroup) {
        Map<String, Object> guest = new HashMap<>();
        guest.put("name", name);
        guest.put("phone", phone);
        guest.put("gender", gender);
        guest.put("ageGroup", ageGroup);
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(storeId));
        b.put("startAt", START);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("guest", guest);
        return b;
    }
}
