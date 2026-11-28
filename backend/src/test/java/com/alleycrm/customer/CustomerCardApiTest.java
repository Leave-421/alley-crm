package com.alleycrm.customer;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import tools.jackson.databind.ObjectMapper;

/** C9 고객 카드 · 메모(FR-CUS-03), C10 손님 등급(FR-CUS-04). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerCardApiTest {

    static final String PASSWORD = "password1";
    /** 2026-12-01 09:00 KST */
    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");
    static final String MEMBER_NAME = "김로코";
    static final String GUEST_PHONE = "01055551234";

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
    CustomerCardService service;

    long regionId;
    long storeA;
    long storeB;
    String ownerA;
    String ownerB;
    String association;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('고객 카드 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("고객 카드 가게 A");
        storeB = store("고객 카드 가게 B");
        ownerA = "ccowner" + System.nanoTime();
        accountService.createOwner(storeA, ownerA, PASSWORD);
        ownerB = "ccowner" + System.nanoTime();
        accountService.createOwner(storeB, ownerB, PASSWORD);
        association = "ccassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        List<Long> customers = jdbc.queryForList(
                "SELECT DISTINCT customer_id FROM reservation WHERE store_id IN (?, ?)", Long.class, storeA, storeB);
        jdbc.update("DELETE FROM customer_card WHERE store_id IN (?, ?)", storeA, storeB);
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL WHERE store_id IN (?, ?)", storeA, storeB);
        jdbc.update("DELETE FROM course_proposal WHERE recommended_store_id IN (?, ?)", storeA, storeB);
        jdbc.update("DELETE FROM reservation WHERE store_id IN (?, ?)", storeA, storeB);
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ? AND type = 'guest'", c);
        }
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'cc%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'cc%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CUS_03_1_가게_A_고객_카드에는_가게_A_방문_이력만_메모_등급이_있다() throws Exception {
        long member = member();
        long first = visit(storeA, member, 10);
        long second = visit(storeA, member, 5);
        visit(storeB, member, 3);
        recommended(second, storeB);
        MockHttpSession owner = login(ownerA);

        String body = mvc.perform(get("/api/v1/owner/customers/{id}", member).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.customerType").value("member"))
                .andExpect(jsonPath("$.customer.maskedName").value("김*코"))
                .andExpect(jsonPath("$.customer.grade").value("regular"))
                .andExpect(jsonPath("$.customer.visitCount").value(2))
                .andExpect(jsonPath("$.customer.recommendedVisitCount").value(1))
                .andExpect(jsonPath("$.visits[*].reservationId").value(Matchers.contains(
                        String.valueOf(second), String.valueOf(first))))
                .andExpect(jsonPath("$.visits[0].fromRecommendation").value(true))
                .andExpect(jsonPath("$.memo").isEmpty())
                .andReturn().getResponse().getContentAsString();
        // 이름 · 보낸 가게는 응답에 없다(FR-CUS-05)
        // ID 문자열로 찾으면 다른 ID(손님 ID 등)와 숫자가 겹칠 때 틀리게 실패하므로 필드 이름으로 본다
        assertThat(body).doesNotContain(MEMBER_NAME).doesNotContain("고객 카드 가게 B").doesNotContain("senderStore");

        memo(owner, member, "  창가 자리 선호  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memo").value("창가 자리 선호"));
        // 메모는 가게마다 따로다
        mvc.perform(get("/api/v1/owner/customers/{id}", member).session(login(ownerB)))
                .andExpect(jsonPath("$.memo").isEmpty())
                .andExpect(jsonPath("$.customer.grade").value("new"))
                .andExpect(jsonPath("$.visits.length()").value(1));
        memo(owner, member, "").andExpect(status().isOk()).andExpect(jsonPath("$.memo").isEmpty());
    }

    @Test
    void FR_CUS_03_2_상인회와_다른_가게_손님은_고객_카드를_볼_수_없다() throws Exception {
        long member = member();
        visit(storeB, member, 3);

        MockHttpSession assoc = login(association);
        mvc.perform(get("/api/v1/owner/customers/{id}", member).session(assoc)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/owner/customers").session(assoc)).andExpect(status().isForbidden());
        // 우리 가게에 예약이 없는 손님은 없는 것처럼 404
        MockHttpSession owner = login(ownerA);
        mvc.perform(get("/api/v1/owner/customers/{id}", member).session(owner)).andExpect(status().isNotFound());
        memo(owner, member, "남의 손님").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/owner/customers/{id}", member)).andExpect(status().isUnauthorized());
    }

    @Test
    void FR_CUS_04_1_2_방문_1_2_3회는_신규_일반_단골_단골이_60일_넘게_안_오면_이탈_위험() throws Exception {
        long once = guest("01010000001", 10);
        long twice = guest("01010000002", 10, 9);
        long thrice = guest("01010000003", 10, 9, 8);
        long lapsed = guest("01010000004", 90, 80, 61);
        long boundary = guest("01010000005", 90, 80, 60);

        assertThat(service.grades(storeA, List.of(once, twice, thrice, lapsed, boundary))).isEqualTo(Map.of(
                once, "new", twice, "regular", thrice, "loyal", lapsed, "at_risk", boundary, "loyal"));
        mvc.perform(get("/api/v1/owner/customers").param("grade", "at_risk").session(login(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(String.valueOf(lapsed)));
    }

    @Test
    void FR_CUS_04_3_4_등급은_그_가게_방문_처리만_센다() throws Exception {
        long elsewhere = guest("01020000001", 10);
        for (int days : new int[] {9, 8, 7, 6, 5}) {
            visit(storeB, elsewhere, days);
        }
        long withCancel = guest("01020000002", 10, 9);
        reservation(storeA, withCancel, "canceled", 5, null);
        long onlyBooked = guest("01020000003");
        reservation(storeA, onlyBooked, "confirmed", -2, null);

        assertThat(service.grades(storeA, List.of(elsewhere, withCancel, onlyBooked)))
                .isEqualTo(Map.of(elsewhere, "new", withCancel, "regular"));
        // 방문 처리가 없는 손님도 목록에는 있고 등급은 null, 최근 예약 순
        mvc.perform(get("/api/v1/owner/customers").session(login(ownerA)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].customerId").value(String.valueOf(onlyBooked)))
                .andExpect(jsonPath("$.items[0].grade").isEmpty());
    }

    @Test
    void 파기된_비회원은_이름_메모_없이_보이고_메모를_쓸_수_없다() throws Exception {
        long purged = guest(GUEST_PHONE, 200);
        jdbc.update("INSERT INTO customer_card (store_id, customer_id, memo) VALUES (?, ?, '파기 전 메모')", storeA, purged);
        jdbc.update("UPDATE customer SET name = NULL, phone = NULL, purged_at = ? WHERE id = ?", utc(NOW), purged);
        MockHttpSession owner = login(ownerA);

        String body = mvc.perform(get("/api/v1/owner/customers/{id}", purged).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.purged").value(true))
                .andExpect(jsonPath("$.customer.maskedName").isEmpty())
                .andExpect(jsonPath("$.memo").isEmpty())
                .andExpect(jsonPath("$.visits.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("파기 전 메모");
        memo(owner, purged, "다시").andExpect(status().isConflict());
    }

    @Test
    void 입력_검사() throws Exception {
        long member = member();
        visit(storeA, member, 1);
        MockHttpSession owner = login(ownerA);

        mvc.perform(get("/api/v1/owner/customers").param("grade", "vip").session(owner)).andExpect(status().isBadRequest());
        memo(owner, member, "가".repeat(501)).andExpect(status().isBadRequest());
        memo(owner, member, "가".repeat(500)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/owner/customers/{id}/memo", member).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"memo\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        // 사장님 응답에 전화번호는 없다
        assertThat(mvc.perform(get("/api/v1/owner/customers").session(owner)).andReturn().getResponse().getContentAsString())
                .doesNotContain("phone");
    }

    private long member() {
        long account = accountService.signupCustomer("ccmember" + System.nanoTime(), PASSWORD, MEMBER_NAME, null, null).id();
        return jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, account);
    }

    /** 가게 A에 visitDaysAgo마다 방문 처리된 예약이 있는 비회원 */
    private long guest(String phone, int... visitDaysAgo) {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '비회원손님', ?)", phone);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int days : visitDaysAgo) {
            visit(storeA, id, days);
        }
        return id;
    }

    private long visit(long storeId, long customerId, int daysAgo) {
        return reservation(storeId, customerId, "visited", daysAgo, 20000);
    }

    private long reservation(long storeId, long customerId, String status, int daysAgo, Integer visitAmount) {
        Instant start = NOW.minus(Duration.ofDays(daysAgo));
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at, visit_amount)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, ?)
                """, "C" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(start), status,
                "canceled".equals(status) ? "customer_canceled" : null, utc(start.minus(Duration.ofDays(1))),
                "visited".equals(status) ? utc(start.plusSeconds(3600)) : null, visitAmount);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 그 예약이 정식 코스 제안으로 온 것으로 표시(기준 예약은 다른 가게) */
    private void recommended(long reservationId, long baseStoreId) {
        long base = jdbc.queryForObject("SELECT id FROM reservation WHERE store_id = ? LIMIT 1", Long.class, baseStoreId);
        jdbc.update("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, ?)",
                base, storeA, utc(NOW));
        long proposal = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE reservation SET course_proposal_id = ? WHERE id = ?", proposal, reservationId);
    }

    private ResultActions memo(MockHttpSession session, long customerId, String memo) throws Exception {
        return mvc.perform(put("/api/v1/owner/customers/{id}/memo", customerId).session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("memo", memo))));
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

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
