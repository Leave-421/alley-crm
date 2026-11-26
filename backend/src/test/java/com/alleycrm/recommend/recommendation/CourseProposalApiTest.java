package com.alleycrm.recommend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 정식 코스 제안 API(CONTRACTS_API 3.1, FR-CRS-02 · FR-ANL-06). 기준 가게 · 기준 시각 · 같은 날은 기준 예약에서 정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CourseProposalApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-12-04 19:00 KST. 신청 마감은 2026-12-05 00:00 KST = 2026-12-04T15:00Z. */
    private static final LocalDateTime BASE_START_UTC = LocalDateTime.of(2026, 12, 4, 10, 0);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccountService accounts;

    @Autowired
    SettingService settings;

    long region;
    long base;
    long dessert;
    long pub;
    long bookstore;
    long memberCustomer;
    long baseReservation;
    MockHttpSession member;

    @BeforeEach
    void seed() throws Exception {
        region = insert("INSERT INTO region (name) VALUES ('코스 제안 권역')");
        base = store("기준 카페", "cafe", 0);
        dessert = store("구름 디저트", "dessert", 10);
        pub = store("달빛 포차", "pub", 50);
        bookstore = store("책갈피", "bookstore", 120);
        String loginId = "c" + System.nanoTime();
        long accountId = accounts.signupCustomer(loginId, PASSWORD, "회원", null, null).id();
        memberCustomer = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        baseReservation = reservation(memberCustomer, base, BASE_START_UTC, "confirmed");
        member = login(loginId);
    }

    @Test
    void 기준_가게와_기준_시각은_그_예약에서_정하고_신청_마감을_준다() throws Exception {
        proposals(member, baseReservation, 0)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.baseReservationId").value(String.valueOf(baseReservation)))
                .andExpect(jsonPath("$.baseStoreId").value(String.valueOf(base)))
                .andExpect(jsonPath("$.baseTime").value("2026-12-04T10:00:00Z"))
                .andExpect(jsonPath("$.applyDeadline").value("2026-12-04T15:00:00Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageCount").value(1))
                .andExpect(jsonPath("$.items[*].rank").value(Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$.items[0].badge").isString())
                .andExpect(jsonPath("$.items[0].reasonText").isString())
                .andExpect(jsonPath("$.baseTimeSource").doesNotExist());
    }

    @Test
    void 같은_날_제외는_기준_예약_날짜로_한다() throws Exception {
        reservation(memberCustomer, dessert, BASE_START_UTC.plusHours(2), "requested"); // 같은 KST 날짜
        reservation(memberCustomer, pub, BASE_START_UTC.plusDays(6), "confirmed"); // 다른 날

        List<String> ids = storeIds(proposals(member, baseReservation, 0).andExpect(status().isOk()));

        assertThat(ids).doesNotContain(String.valueOf(dessert), String.valueOf(base))
                .contains(String.valueOf(pub), String.valueOf(bookstore));
    }

    @Test
    void 묶음은_설정값만큼_끝까지_보면_처음으로_돌아온다() throws Exception {
        settings.set(region, SettingKey.RECOMMEND_COUNT, "2");

        proposals(member, baseReservation, 1).andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(3));
        proposals(member, baseReservation, 2).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void 남의_예약이나_없는_예약은_404_값이_틀리면_400() throws Exception {
        long other = insert("INSERT INTO customer (type) VALUES ('member')");
        long othersReservation = reservation(other, base, BASE_START_UTC, "confirmed");

        for (long id : new long[] {othersReservation, 999_999_999L}) {
            proposals(member, id, 0).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
            record(member, id, List.of(dessert)).andExpect(status().isNotFound());
        }
        for (String q : new String[] {"", "baseReservationId=abc", "baseReservationId=" + baseReservation + "&page=-1"}) {
            mvc.perform(get("/api/v1/course-proposals?" + q).session(member))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        assertThat(count(othersReservation)).isZero();
    }

    @Test
    void 비로그인은_401_사장님은_403() throws Exception {
        mvc.perform(get("/api/v1/course-proposals").param("baseReservationId", String.valueOf(baseReservation)))
                .andExpect(status().isUnauthorized());
        String ownerId = "o" + System.nanoTime();
        accounts.createOwner(base, ownerId, PASSWORD);
        MockHttpSession owner = login(ownerId);

        proposals(owner, baseReservation, 0).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        record(owner, baseReservation, List.of(dessert)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    @Test
    void 표시_기록은_조합당_1건이고_다시_보내면_같은_ID와_처음_시각을_준다_FR_CRS_02_3() throws Exception {
        JsonNode first = items(record(member, baseReservation, List.of(dessert, pub)).andExpect(status().isOk()));
        JsonNode again = items(record(member, baseReservation, List.of(pub, bookstore)).andExpect(status().isOk()));

        assertThat(first.get(0).get("storeId").asString()).isEqualTo(String.valueOf(dessert));
        assertThat(first.get(1).get("firstShownAt").asString()).endsWith("Z");
        assertThat(again.get(0).get("courseProposalId").asString()).isEqualTo(first.get(1).get("courseProposalId").asString());
        assertThat(again.get(0).get("firstShownAt").asString()).isEqualTo(first.get(1).get("firstShownAt").asString());
        assertThat(again.get(1).get("courseProposalId").asString())
                .isNotIn(first.get(0).get("courseProposalId").asString(), first.get(1).get("courseProposalId").asString());
        assertThat(count(baseReservation)).isEqualTo(3); // 리롤로 처음 나온 책갈피만 새로
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_exposure WHERE base_store_id = ?",
                Integer.class, base)).isZero(); // 탐색 노출은 남기지 않는다
    }

    @Test
    void 후보가_아닌_가게가_있으면_409이고_아무것도_기록하지_않는다() throws Exception {
        reservation(memberCustomer, dessert, BASE_START_UTC.plusHours(2), "requested"); // 같은 날 예약 → 후보 아님

        record(member, baseReservation, List.of(pub, dessert))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        record(member, baseReservation, List.of(base)).andExpect(status().isConflict());
        assertThat(count(baseReservation)).isZero();
    }

    @Test
    void 표시_기록_값이_틀리면_400_CSRF가_없으면_403() throws Exception {
        record(member, baseReservation, List.of()).andExpect(status().isBadRequest());
        record(member, baseReservation, List.of(dessert, dessert)).andExpect(status().isBadRequest());
        record(member, baseReservation, List.of(dessert, pub, bookstore, base)).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/course-proposals").session(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseReservationId\":\"" + baseReservation + "\",\"storeIds\":[\"" + dessert + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(count(baseReservation)).isZero();
    }

    // ---- 준비 ----

    private ResultActions proposals(MockHttpSession session, long reservationId, int page) throws Exception {
        return mvc.perform(get("/api/v1/course-proposals").param("baseReservationId", String.valueOf(reservationId))
                .param("page", String.valueOf(page)).session(session));
    }

    private ResultActions record(MockHttpSession session, long reservationId, List<Long> storeIds) throws Exception {
        String ids = String.join(",", storeIds.stream().map(id -> "\"" + id + "\"").toList());
        return mvc.perform(post("/api/v1/course-proposals").session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseReservationId\":\"" + reservationId + "\",\"storeIds\":[" + ids + "]}"));
    }

    private JsonNode items(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("items");
    }

    private List<String> storeIds(ResultActions result) throws Exception {
        return items(result).valueStream().map(i -> i.get("store").get("id").asString()).toList();
    }

    private int count(long reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM course_proposal WHERE base_reservation_id = ?", Integer.class,
                reservationId);
    }

    private long reservation(long customer, long store, LocalDateTime startUtc, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), store, customer, startUtc, status,
                startUtc.minusDays(1));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long store(String name, String category, int x) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, map_x, map_y) VALUES ("
                + region + ", '" + name + "', '" + category + "', 'center', 'approved', " + x + ", 0)");
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
