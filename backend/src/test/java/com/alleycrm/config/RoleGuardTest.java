package com.alleycrm.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** C3 역할별 열람 범위 서버 가드 (FR-CUS-05, NFR-03). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoleGuardTest {

    static final String PASSWORD = "password1";
    static final String FORBIDDEN = "{\"error\":{\"code\":\"forbidden_role\"}}";

    /** 손님에게 닫힌 흐름 · 매출 · 집계 조회 */
    static final List<String> FLOW_AND_SALES = List.of(
            "/api/v1/owner/customer-flows", "/api/v1/owner/referral-stats",
            "/api/v1/owner/referral-fees?month=2026-11", "/api/v1/association/visit-flows", "/api/v1/association/settlements/2026-11");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountService accountService;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlers;

    long regionId;
    long storeA;
    long storeB;
    long guest;
    long reservationAtA;
    String customer;
    String ownerA;
    String association;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO region (name) VALUES ('역할 가드 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("가드 가게 A");
        storeB = store("가드 가게 B");
        customer = "rgcustomer" + System.nanoTime();
        accountService.signupCustomer(customer, PASSWORD, "회원", null, null);
        ownerA = "rgowner" + System.nanoTime();
        accountService.createOwner(storeA, ownerA, PASSWORD);
        association = "rgassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);

        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '가드손님', '01077778888')");
        guest = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LocalDateTime at = LocalDateTime.of(2026, 11, 20, 3, 0);
        reservation(storeB, null, at);
        reservationAtA = reservation(storeA, storeB, at.plusHours(2)); // B에서 보낸 소개 예약
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM reservation WHERE store_id IN (?, ?)", storeA, storeB);
        jdbc.update("DELETE FROM customer WHERE id = ?", guest);
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'rg%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'rg%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CUS_05_1_사장님은_다른_가게_방문_이력을_받지_못하고_추천_여부만_받는다() throws Exception {
        MockHttpSession owner = login(ownerA);

        // 우리 가게 예약에는 추천으로 왔는지만 있고, 보낸 가게는 이름 · ID 모두 없다
        mvc.perform(get("/api/v1/owner/reservations/{id}", reservationAtA).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromRecommendation").isBoolean())
                .andExpect(jsonPath("$.senderStoreId").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("가드 가게 B"))));
        // 다른 가게 예약 · 고객 카드의 다른 가게 방문은 받지 못한다
        long reservationAtB = jdbc.queryForObject("SELECT id FROM reservation WHERE store_id = ?", Long.class, storeB);
        mvc.perform(get("/api/v1/owner/reservations/{id}", reservationAtB).session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/owner/customers/{id}", guest).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits[*].reservationId").value(Matchers.contains(String.valueOf(reservationAtA))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("가드 가게 B"))));
    }

    @Test
    void FR_CUS_05_2_손님_계정은_흐름_매출_데이터를_받지_못한다() throws Exception {
        MockHttpSession session = login(customer);
        for (String path : FLOW_AND_SALES) {
            mvc.perform(get(path).session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(content().json(FORBIDDEN));
        }
    }

    @Test
    void NFR_03_1_다른_역할의_API를_직접_불러도_거절한다() throws Exception {
        MockHttpSession owner = login(ownerA);
        MockHttpSession assoc = login(association);

        expectForbidden(get("/api/v1/association/visit-flows").session(owner));
        expectForbidden(get("/api/v1/association/visit-reports").session(owner));
        expectForbidden(put("/api/v1/association/share-policy/party_size").session(owner).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"));

        expectForbidden(get("/api/v1/owner/reservations").param("date", "2026-11-20").session(assoc));
        expectForbidden(get("/api/v1/owner/customers/{id}", guest).session(assoc));
        expectForbidden(post("/api/v1/owner/reservations/{id}/confirm", reservationAtA).session(assoc).with(TestCsrf.csrf(mvc)));

        // 로그인하지 않으면 역할 검사 전에 401
        mvc.perform(get("/api/v1/owner/segments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/association/visit-flows")).andExpect(status().isUnauthorized());
    }

    /** 새 API가 역할 검사를 빠뜨리면 여기서 잡힌다: 사장님 · 상인회 경로 전부를 다른 역할(손님 · 상대 역할)로 부른다. */
    @Test
    void NFR_03_1_사장님_상인회_경로는_모두_다른_역할을_거절한다() throws Exception {
        MockHttpSession customerSession = login(customer);
        MockHttpSession owner = login(ownerA);
        MockHttpSession assoc = login(association);
        List<String> checked = new java.util.ArrayList<>();
        List<String> violations = new java.util.ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlers.getHandlerMethods().entrySet()) {
            for (String pattern : e.getKey().getPatternValues()) {
                boolean ownerPath = pattern.startsWith("/api/v1/owner/");
                if (!ownerPath && !pattern.startsWith("/api/v1/association/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    for (MockHttpSession wrong : List.of(customerSession, ownerPath ? assoc : owner)) {
                        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method.name()), path).session(wrong)
                                .contentType(MediaType.APPLICATION_JSON).content("{}");
                        if (method != RequestMethod.GET) {
                            request.with(TestCsrf.csrf(mvc));
                        }
                        int status = mvc.perform(request).andReturn().getResponse().getStatus();
                        // 400: 본문 · 매개변수 검사가 역할 검사보다 먼저 거절한 경우(데이터는 나가지 않는다)
                        if (status != 403 && status != 400) {
                            violations.add(method + " " + pattern + " → " + status);
                        }
                    }
                    checked.add(method + " " + pattern);
                }
            }
        }
        assertThat(violations).isEmpty();
        assertThat(checked).as("훑은 경로").hasSizeGreaterThan(20);
    }

    @Test
    void NFR_03_1_상인회_흐름_응답에는_개인_단위_값이_없다() throws Exception {
        mvc.perform(get("/api/v1/association/visit-flows").session(login(association)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.anyOf(
                        Matchers.containsString("customerId"), Matchers.containsString("가드손님"),
                        Matchers.containsString("01077778888"), Matchers.containsString("reservationId")))));
    }

    private void expectForbidden(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(content().json(FORBIDDEN));
    }

    private long reservation(long storeId, Long senderStoreId, LocalDateTime startAt) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at,
                                         visited_at, sender_store_id)
                VALUES (?, ?, ?, ?, 2, 'visited', ?, ?, ?)
                """, "G" + System.nanoTime() % 1_000_000_000L, storeId, guest, startAt, startAt.minusDays(1), startAt, senderStoreId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long store(String name) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, 'cafe', 'center', 'approved', '10:00', '22:00', 5)
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
