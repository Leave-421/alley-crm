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
import java.math.BigDecimal;
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
 * 지도 추천 API(CONTRACTS_API 3.1, FR-CPN-02 · FR-CRS-05 · 06 · 10). 로그인 없이 여는 보안 설정(C, R-10) 전이라 회원 세션으로 부른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecommendationApiTest {

    private static final String PASSWORD = "password1";

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
    MockHttpSession member;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM public_stat");
        jdbc.update("""
                INSERT INTO public_stat (kind, category_to, value, source, period)
                VALUES ('attractiveness', 'dessert', 0.6, '서울시 상권분석서비스 추정매출-상권 (OA-15572)', '2025년 4분기'),
                       ('attractiveness', 'pub', 0.4, '서울시 상권분석서비스 추정매출-상권 (OA-15572)', '2025년 4분기')
                """);
        region = insert("INSERT INTO region (name) VALUES ('추천 API 권역')");
        base = store("기준 카페", "cafe", 0);
        dessert = store("구름 디저트", "dessert", 10);
        pub = store("달빛 포차", "pub", 50);
        bookstore = store("책갈피", "bookstore", 120);
        String loginId = "r" + System.nanoTime();
        accounts.signupCustomer(loginId, PASSWORD, "회원", null, null);
        member = login(loginId);
    }

    @Test
    void 추천_카드는_순위_가게_배지_이유_쿠폰_혜택을_주고_숫자_근거가_없다_FR_CRS_05_1() throws Exception {
        String body = recommend(base, 0)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.baseStoreId").value(String.valueOf(base)))
                .andExpect(jsonPath("$.baseTimeSource").value("now"))
                .andExpect(jsonPath("$.baseTime").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageCount").value(1))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].rank").value(Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$.items[0].store.id").isString())
                .andExpect(jsonPath("$.items[0].store.category").isString())
                .andExpect(jsonPath("$.items[0].store.alley").value("center"))
                .andExpect(jsonPath("$.items[0].badge").value("local_popular"))
                .andExpect(jsonPath("$.items[0].couponBenefit").value(false))
                .andExpect(jsonPath("$.publicData.source").value(Matchers.containsString("서울시 상권분석서비스")))
                .andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(body).get("items");
        for (JsonNode item : items) {
            assertThat(item.get("reasonText").asString()).doesNotContainPattern("[0-9]");
            assertThat(item.has("score")).isFalse();
            assertThat(item.get("store").has("distance")).isFalse();
        }
        assertThat(items.valueStream().map(i -> i.get("store").get("id").asString()).toList())
                .doesNotContain(String.valueOf(base)); // 기준 가게는 후보가 아님
    }

    @Test
    void 쿠폰_행사를_켠_가게는_예약_혜택이_켜지고_순위는_같다_FR_CPN_02_1() throws Exception {
        List<String> before = order(recommend(base, 0));
        insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + bookstore + ", 'fixed_discount', 3000, 7, 10, 0, UTC_TIMESTAMP(6))");

        ResultActions after = recommend(base, 0);

        assertThat(order(after)).isEqualTo(before);
        after.andExpect(jsonPath("$.items[?(@.store.id == '" + bookstore + "')].couponBenefit").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.items[?(@.store.id == '" + dessert + "')].couponBenefit").value(Matchers.contains(false)));
    }

    @Test
    void A에서_B로_5명_이상이고_A_방문_10명이면_방문_데이터_기반_FR_CRS_10_1() throws Exception {
        visits(10, pub);

        String pubCard = "$.items[?(@.store.id == '" + pub + "')]";
        recommend(base, 0)
                .andExpect(jsonPath(pubCard + ".badge").value(Matchers.contains("visit_data")))
                .andExpect(jsonPath(pubCard + ".reasonType").value(Matchers.contains("visit_data")))
                .andExpect(jsonPath(pubCard + ".reasonText").value(Matchers.contains("이 가게 손님들이 같은 날 자주 찾는 참여 가게예요")))
                .andExpect(jsonPath("$.items[?(@.store.id == '" + dessert + "')].badge").value(Matchers.contains("local_popular")));
    }

    @Test
    void A에서_B로_5명_이상이어도_A_방문_8명이면_지역_인기_가게_FR_CRS_10_2() throws Exception {
        visits(5, pub);
        visits(3, null);

        recommend(base, 0)
                .andExpect(jsonPath("$.items[?(@.store.id == '" + pub + "')].badge").value(Matchers.contains("local_popular")));
    }

    @Test
    void 묶음은_설정값만큼_끝까지_보면_처음으로_돌아온다_FR_CRS_06() throws Exception {
        settings.set(region, SettingKey.RECOMMEND_COUNT, "2");

        recommend(base, 0).andExpect(jsonPath("$.pageCount").value(2)).andExpect(jsonPath("$.items.length()").value(2));
        recommend(base, 1).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.items[0].rank").value(3));
        recommend(base, 2).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.items[0].rank").value(1));
    }

    @Test
    void 기준_가게가_없거나_보이지_않으면_404_값이_틀리면_400() throws Exception {
        long requested = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '신청 중', 'cafe', 'center', 'requested')");
        for (long id : new long[] {requested, 999_999_999L}) {
            recommend(id, 0).andExpect(status().isNotFound());
        }
        for (String q : new String[] {"", "baseStoreId=abc", "baseStoreId=" + base + "&page=-1"}) {
            mvc.perform(get("/api/v1/recommendations?" + q).session(member))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void 그린_카드를_기록하면_노출_ID를_주고_다시_계산한_순위_배지를_남긴다() throws Exception {
        String displayId = UUID.randomUUID().toString();

        String body = expose(displayId, base, List.of(dessert, pub))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].storeId").value(Matchers.contains(String.valueOf(dessert), String.valueOf(pub))))
                .andExpect(jsonPath("$.items[0].exposureId").isString())
                .andReturn().getResponse().getContentAsString();

        long exposureId = Long.parseLong(json.readTree(body).get("items").get(0).get("exposureId").asString());
        assertThat(jdbc.queryForObject("SELECT badge FROM recommendation_exposure WHERE id = ?", String.class, exposureId))
                .isEqualTo("local_popular");
        assertThat(jdbc.queryForObject("SELECT `rank` FROM recommendation_exposure WHERE id = ?", Integer.class, exposureId))
                .isPositive();
    }

    @Test
    void 후보가_아닌_가게가_있으면_409이고_아무것도_기록하지_않는다() throws Exception {
        long otherRegion = insert("INSERT INTO region (name) VALUES ('다른 권역')");
        long outside = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + otherRegion + ", '밖', 'cafe', 'center', 'approved')");

        expose(UUID.randomUUID().toString(), base, List.of(dessert, outside))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        expose(UUID.randomUUID().toString(), base, List.of(base)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_exposure WHERE base_store_id = ?",
                Integer.class, base)).isZero();
    }

    @Test
    void 표시_기록_값이_틀리면_400_CSRF가_없으면_403() throws Exception {
        expose("not-a-uuid", base, List.of(dessert)).andExpect(status().isBadRequest());
        expose(UUID.randomUUID().toString(), base, List.of(dessert, pub, bookstore, dessert)).andExpect(status().isBadRequest());
        expose(UUID.randomUUID().toString(), base, List.of(dessert, dessert)).andExpect(status().isBadRequest());
        expose(UUID.randomUUID().toString(), base, List.of()).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/recommendation-exposures").session(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayId\":\"" + UUID.randomUUID() + "\",\"baseStoreId\":\"" + base
                                + "\",\"storeIds\":[\"" + dessert + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    // ---- 준비 ----

    private ResultActions recommend(long baseStoreId, int page) throws Exception {
        return mvc.perform(get("/api/v1/recommendations").param("baseStoreId", String.valueOf(baseStoreId))
                .param("page", String.valueOf(page)).session(member));
    }

    private ResultActions expose(String displayId, long baseStoreId, List<Long> storeIds) throws Exception {
        String ids = String.join(",", storeIds.stream().map(id -> "\"" + id + "\"").toList());
        return mvc.perform(post("/api/v1/recommendation-exposures").session(member).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayId\":\"" + displayId + "\",\"baseStoreId\":\"" + baseStoreId
                        + "\",\"page\":0,\"storeIds\":[" + ids + "]}"));
    }

    private List<String> order(ResultActions result) throws Exception {
        JsonNode items = json.readTree(result.andReturn().getResponse().getContentAsString()).get("items");
        return items.valueStream().map(i -> i.get("store").get("id").asString()).toList();
    }

    /** 손님 {@code count}명이 같은 날 기준 가게를 방문하고(next가 있으면) 이어서 next를 방문. */
    private void visits(int count, Long next) {
        for (int i = 0; i < count; i++) {
            long customer = insert("INSERT INTO customer (type) VALUES ('member')");
            visit(customer, base, LocalDateTime.of(2026, 10, 2, 3, 0));
            if (next != null) {
                visit(customer, next, LocalDateTime.of(2026, 10, 2, 5, 0));
            }
        }
    }

    private void visit(long customer, long store, LocalDateTime startUtc) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, 'visited', ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), store, customer, startUtc, startUtc.minusDays(1));
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
