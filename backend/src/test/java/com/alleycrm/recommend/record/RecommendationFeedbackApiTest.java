package com.alleycrm.recommend.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 거절 · 리롤 기록(CONTRACTS_API 3.11, FR-CRS-06). 로그인 없이 여는 보안 설정(C, N-2) 전이라 회원 세션으로 부르고,
 * 노출 주인은 이 브라우저의 방문자 쿠키(3.3)로 정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecommendationFeedbackApiTest {

    private static final String PASSWORD = "password1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccountService accounts;

    long base;
    long dessert;
    long pub;
    long bookstore;
    MockHttpSession member;
    Cookie visitor;
    List<String> exposureIds;

    @BeforeEach
    void seed() throws Exception {
        long region = insert("INSERT INTO region (name) VALUES ('추천 피드백 권역')");
        base = store(region, "기준 카페", "cafe", 0);
        dessert = store(region, "구름 디저트", "dessert", 10);
        pub = store(region, "달빛 포차", "pub", 50);
        bookstore = store(region, "책갈피", "bookstore", 120);
        member = member();
        // 카드 묶음을 그렸다고 기록하면 방문자 쿠키와 노출 ID를 받는다(3.1 · 3.3)
        MvcResult shown = mvc.perform(post("/api/v1/recommendation-exposures").session(member).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayId\":\"" + UUID.randomUUID() + "\",\"baseStoreId\":\"" + base
                                + "\",\"page\":0,\"storeIds\":[\"" + dessert + "\",\"" + pub + "\",\"" + bookstore + "\"]}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = shown.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("alley_vid=")).findFirst().orElseThrow();
        visitor = new Cookie("alley_vid", setCookie.substring("alley_vid=".length(), setCookie.indexOf(';')));
        JsonNode items = json.readTree(shown.getResponse().getContentAsString()).get("items");
        exposureIds = items.valueStream().map(i -> i.get("exposureId").asString()).toList();
    }

    @Test
    void 거절은_사유_없이_기록되고_같은_노출은_한_번_순위는_같다_FR_CRS_06_3() throws Exception {
        List<String> before = order();

        feedback("reject", List.of(exposureIds.get(0)), member, visitor).andExpect(status().isNoContent());
        feedback("reject", List.of(exposureIds.get(0)), member, visitor).andExpect(status().isNoContent());

        assertThat(count("reject", exposureIds.get(0))).isEqualTo(1);
        assertThat(order()).isEqualTo(before); // 거절은 순위에 쓰지 않는다
    }

    @Test
    void 리롤은_지금_묶음의_노출마다_기록된다() throws Exception {
        feedback("reroll", exposureIds, member, visitor).andExpect(status().isNoContent());

        for (String id : exposureIds) {
            assertThat(count("reroll", id)).isEqualTo(1);
            assertThat(count("reject", id)).isZero();
        }
    }

    @Test
    void 남의_노출이면_404이고_아무것도_남기지_않는다() throws Exception {
        MockHttpSession other = member();

        feedback("reject", List.of(exposureIds.get(0)), other, null) // 쿠키 없는 다른 브라우저 · 다른 회원
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        feedback("reject", List.of(exposureIds.get(0), "999999999"), member, visitor).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_feedback WHERE exposure_id IN (?, ?, ?)",
                Integer.class, exposureIds.toArray())).isZero();
    }

    @Test
    void 값이_틀리면_400_CSRF가_없으면_403() throws Exception {
        String id = exposureIds.get(0);
        for (String body : List.of(
                "{\"type\":\"like\",\"exposureIds\":[\"" + id + "\"]}",
                "{\"type\":\"reject\",\"exposureIds\":[]}",
                "{\"type\":\"reject\",\"exposureIds\":[\"" + id + "\",\"" + id + "\"]}",
                "{\"type\":\"reject\",\"exposureIds\":[\"1\",\"2\",\"3\",\"4\"]}",
                "{\"type\":\"reject\",\"exposureIds\":[\"abc\"]}",
                "{\"exposureIds\":[\"" + id + "\"]}",
                "")) {
            send(body, member, visitor, true).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        send("{\"type\":\"reject\",\"exposureIds\":[\"" + id + "\"]}", member, visitor, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(count("reject", id)).isZero();
    }

    // ---- 준비 ----

    private ResultActions feedback(String type, List<String> ids, MockHttpSession session, Cookie cookie) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"exposureIds\":[" + String.join(",",
                ids.stream().map(i -> "\"" + i + "\"").toList()) + "]}";
        return send(body, session, cookie, true);
    }

    private ResultActions send(String body, MockHttpSession session, Cookie cookie, boolean csrf) throws Exception {
        var request = post("/api/v1/recommendation-feedback").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (csrf) {
            request.with(TestCsrf.csrf(mvc));
        }
        if (cookie != null) { // TestCsrf가 쿠키를 갈아 끼우므로 그 뒤에 방문자 쿠키를 더한다
            request.with(r -> {
                Cookie[] cookies = r.getCookies() == null ? new Cookie[0] : r.getCookies();
                Cookie[] withVisitor = Arrays.copyOf(cookies, cookies.length + 1);
                withVisitor[cookies.length] = cookie;
                r.setCookies(withVisitor);
                return r;
            });
        }
        return mvc.perform(request);
    }

    private List<String> order() throws Exception {
        String body = mvc.perform(get("/api/v1/recommendations").param("baseStoreId", String.valueOf(base)).session(member))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("items").valueStream().map(i -> i.get("store").get("id").asString()).toList();
    }

    private int count(String type, String exposureId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_feedback WHERE type = ? AND exposure_id = ?",
                Integer.class, type, Long.parseLong(exposureId));
    }

    private MockHttpSession member() throws Exception {
        String loginId = "f" + System.nanoTime();
        accounts.signupCustomer(loginId, PASSWORD, "회원", null, null);
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long store(long region, String name, String category, int x) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, map_x, map_y) VALUES ("
                + region + ", '" + name + "', '" + category + "', 'center', 'approved', " + x + ", 0)");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
