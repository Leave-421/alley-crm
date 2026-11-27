package com.alleycrm.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** C20 상인회 방문 흐름: 확인된 연계 방문 · 추천 노출 · 인기 연결 TOP 3 · 5명 미만 숨김 (FR-ANL-03 · 05). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VisitFlowApiTest {

    static final String PASSWORD = "password1";
    /** 2026-11-20 10:00 KST */
    static final Instant DAY = Instant.parse("2026-11-20T01:00:00Z");
    static final long HOUR = 3600;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;

    long regionId;
    long otherRegionId;
    long a;
    long b;
    long c;
    long withdrawn;
    String association;
    String otherAssociation;
    String owner;
    final List<Long> customers = new ArrayList<>();
    final List<Long> visitors = new ArrayList<>();

    @BeforeEach
    void seed() {
        regionId = region("방문 흐름 권역");
        otherRegionId = region("방문 흐름 다른 권역");
        a = store("흐름 A", "approved");
        b = store("흐름 B", "approved");
        c = store("흐름 C", "withdraw_requested");
        withdrawn = store("흐름 탈퇴", "withdrawn");
        association = "vfassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        otherAssociation = "vfassoc" + System.nanoTime();
        accountService.createAssociation(otherRegionId, otherAssociation, PASSWORD);
        owner = "vfowner" + System.nanoTime();
        accountService.createOwner(a, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM recommendation_exposure WHERE base_store_id IN (SELECT id FROM store WHERE region_id = ?)", regionId);
        jdbc.update("DELETE FROM reservation WHERE store_id IN (SELECT id FROM store WHERE region_id = ?)", regionId);
        for (long v : visitors) {
            jdbc.update("DELETE FROM visitor WHERE id = ?", v);
        }
        for (long cu : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", cu);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'vf%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id IN (?, ?)", regionId, otherRegionId);
    }

    @Test
    void FR_ANL_03_1_3_연결마다_확인된_연계_방문_수와_인기_연결_TOP_3() throws Exception {
        moves(a, b, 5);
        moves(b, c, 7);
        moves(c, a, 6);
        moves(a, c, 5);

        JsonNode body = flows(login(association)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(json::readTree);

        assertThat(count(body, "links", a, b)).isEqualTo(5);
        assertThat(count(body, "links", b, c)).isEqualTo(7);
        assertThat(body.get("top").size()).isEqualTo(3);
        assertThat(body.get("top").get(0).get("fromStoreId").asString()).isEqualTo(String.valueOf(b));
        assertThat(body.get("top").get(1).get("fromStoreId").asString()).isEqualTo(String.valueOf(c));
        // 5로 같으면 가게 ID 순: A → B가 A → C보다 앞
        assertThat(body.get("top").get(2).get("toStoreId").asString()).isEqualTo(String.valueOf(b));
    }

    @Test
    void FR_ANL_03_2_두_가게_모두_방문_처리된_같은_날_연달아_이동만_센다() throws Exception {
        moves(a, b, 5);
        // 같은 날 A 방문 뒤 B 예약 취소 → 세지 않음
        long canceled = guest();
        visit(a, canceled, DAY, "visited");
        visit(b, canceled, DAY.plusSeconds(2 * HOUR), "canceled");
        // 다른 날 → 세지 않음
        long nextDay = guest();
        visit(a, nextDay, DAY, "visited");
        visit(b, nextDay, DAY.plusSeconds(26 * HOUR), "visited");
        // 운영 시간대 날짜가 다름: 23:00 KST → 다음 날 00:30 KST (UTC로는 같은 날) → 세지 않음
        long midnight = guest();
        visit(a, midnight, Instant.parse("2026-11-20T14:00:00Z"), "visited");
        visit(b, midnight, Instant.parse("2026-11-20T15:30:00Z"), "visited");
        // 같은 가게를 연달아 → 이동이 아님
        long sameStore = guest();
        visit(a, sameStore, DAY, "visited");
        visit(a, sameStore, DAY.plusSeconds(3 * HOUR), "visited");

        JsonNode body = flows(login(association)).andReturn().getResponse().getContentAsString().transform(json::readTree);

        assertThat(count(body, "links", a, b)).isEqualTo(5);
        assertThat(find(body, "links", a, a)).isNull();
    }

    @Test
    void FR_ANL_03_4_5_추천_노출과_확인된_이동은_나뉘고_5명_미만은_숨긴다() throws Exception {
        moves(a, b, 4);
        exposures(a, b, 5);
        exposures(b, a, 2);

        String raw = flows(login(association))
                .andExpect(jsonPath("$.top").isEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(raw);

        JsonNode ab = find(body, "links", a, b);
        assertThat(ab.get("moves").get("belowMinimum").asBoolean()).isTrue();
        assertThat(ab.get("moves").get("count").isNull()).isTrue();
        assertThat(ab.get("exposures").get("count").asLong()).isEqualTo(5);
        JsonNode ba = find(body, "links", b, a);
        // 확인된 이동이 없으면 0(아무도 드러나지 않음), 노출 2명은 숨김
        assertThat(ba.get("moves").get("count").asLong()).isZero();
        assertThat(ba.get("exposures").get("belowMinimum").asBoolean()).isTrue();
        // 개인 단위 값(손님 · 방문자 ID)은 없다(FR-ANL-05 #1)
        assertThat(raw).doesNotContain("customer").doesNotContain("visitor");
    }

    @Test
    void 탈퇴_가게_연결은_그리지_않고_탈퇴_신청_중인_가게는_그린다() throws Exception {
        moves(a, withdrawn, 6);
        moves(a, c, 6);

        JsonNode body = flows(login(association)).andReturn().getResponse().getContentAsString().transform(json::readTree);

        assertThat(find(body, "links", a, withdrawn)).isNull();
        assertThat(count(body, "links", a, c)).isEqualTo(6);
    }

    @Test
    void 상인회만_자기_권역만() throws Exception {
        moves(a, b, 5);

        flows(login(otherAssociation)).andExpect(status().isOk()).andExpect(jsonPath("$.links").isEmpty());
        flows(login(owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/association/visit-flows")).andExpect(status().isUnauthorized());
    }

    /** n명의 손님이 같은 날 from(10시) → to(12시)를 연달아 방문 처리 */
    private void moves(long from, long to, int n) {
        for (int i = 0; i < n; i++) {
            long customer = guest();
            visit(from, customer, DAY, "visited");
            visit(to, customer, DAY.plusSeconds(2 * HOUR), "visited");
        }
    }

    /** n명의 방문자가 from을 기준으로 to 추천 카드를 봄 */
    private void exposures(long from, long to, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO visitor (anon_id) VALUES (?)", UUID.randomUUID().toString());
            long visitor = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            visitors.add(visitor);
            jdbc.update("""
                    INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, badge, shown_at)
                    VALUES (?, ?, ?, ?, 1, 'visit_data', ?)
                    """, visitor, UUID.randomUUID().toString(), from, to, utc(DAY));
        }
    }

    private long guest() {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '흐름손님', ?)",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        return id;
    }

    private void visit(long storeId, long customerId, Instant start, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, "F" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(start), status,
                "canceled".equals(status) ? "customer_canceled" : null, utc(start.minusSeconds(86_400)),
                "visited".equals(status) ? utc(start.plusSeconds(HOUR)) : null);
    }

    private static JsonNode find(JsonNode body, String field, long from, long to) {
        for (JsonNode link : body.get(field)) {
            if (link.get("fromStoreId").asString().equals(String.valueOf(from))
                    && link.get("toStoreId").asString().equals(String.valueOf(to))) {
                return link;
            }
        }
        return null;
    }

    private static long count(JsonNode body, String field, long from, long to) {
        JsonNode link = find(body, field, from, to);
        assertThat(link).as("%d → %d 연결", from, to).isNotNull();
        assertThat(link.get("moves").get("belowMinimum").asBoolean()).isFalse();
        return link.get("moves").get("count").asLong();
    }

    private ResultActions flows(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/v1/association/visit-flows").session(session));
    }

    private long region(String name) {
        jdbc.update("INSERT INTO region (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long store(String name, String joinStatus) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, 'cafe', 'center', ?, '10:00', '22:00', 5)
                """, regionId, name, joinStatus);
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
