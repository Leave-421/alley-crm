package com.alleycrm.recommend.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/v1/course-previews} (CONTRACTS_API 3.3, FR-CRS-07 #4).
 * 로그인 없이 여는 것은 C의 보안 설정(3.3 V-2) 뒤라, 여기서는 로그인한 요청으로 응답 · 검사를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@WithMockUser
class CoursePreviewControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long a;
    long b;
    long c;

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('오늘의 코스 기록 권역')");
        a = store("가", "approved");
        b = store("나", "approved");
        c = store("다", "approved");
    }

    @Test
    void 노출을_기록하면_204이고_방문자_쿠키와_코스_칸이_남는다() throws Exception {
        preview(body("impression", a, b, c, "weekend", "evening"), null)
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        Matchers.hasItem(Matchers.startsWith("alley_vid="))));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT type, CAST(store_ids AS CHAR) AS store_ids, day_type, time_band, visitor_id
                FROM course_preview_event WHERE id = (SELECT MAX(id) FROM course_preview_event)
                """);
        assertThat(row.get("type")).isEqualTo("impression");
        assertThat(row.get("store_ids")).isEqualTo("[" + a + ", " + b + ", " + c + "]");
        assertThat(row.get("day_type")).isEqualTo("weekend");
        assertThat(row.get("time_band")).isEqualTo("evening");
    }

    @Test
    void 같은_브라우저의_클릭은_같은_방문자로_남고_제안_노출은_만들지_않는다_FR_CRS_07_4() throws Exception {
        int proposals = count("course_proposal");
        int exposures = count("recommendation_exposure");
        MvcResult first = preview(body("impression", a, b, c, "weekday", "lunch"), null)
                .andExpect(status().isNoContent()).andReturn();
        String setCookie = first.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("alley_vid=")).findFirst().orElseThrow();
        Cookie visitor = new Cookie("alley_vid", setCookie.substring("alley_vid=".length(), setCookie.indexOf(';')));

        preview(body("click", a, b, c, "weekday", "lunch"), visitor).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT visitor_id FROM course_preview_event
                WHERE id > (SELECT MAX(id) - 2 FROM course_preview_event)
                """, Long.class)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT type FROM course_preview_event ORDER BY id DESC LIMIT 2", String.class))
                .containsExactly("click", "impression");
        assertThat(count("course_proposal")).isEqualTo(proposals);
        assertThat(count("recommendation_exposure")).isEqualTo(exposures);
    }

    @Test
    void 값이_틀리면_400_invalid() throws Exception {
        String[] bodies = {
            body("view", a, b, c, "weekend", "evening"),
            body("impression", a, b, c, "holiday", "evening"),
            body("impression", a, b, c, "weekend", "dinner"),
            body("impression", a, a, c, "weekend", "evening"),
            "{\"type\":\"impression\",\"storeIds\":[\"" + a + "\",\"" + b + "\"],\"dayType\":\"weekend\",\"timeBand\":\"evening\"}",
            "{\"type\":\"impression\",\"storeIds\":[\"" + a + "\",\"" + b + "\",\"" + c + "\",\"" + (c + 1)
                    + "\"],\"dayType\":\"weekend\",\"timeBand\":\"evening\"}",
            "{\"type\":\"impression\",\"storeIds\":[\"abc\",\"" + b + "\",\"" + c + "\"],\"dayType\":\"weekend\",\"timeBand\":\"evening\"}",
            "{\"type\":\"impression\",\"dayType\":\"weekend\",\"timeBand\":\"evening\"}",
            "{}",
            ""
        };
        int before = count("course_preview_event");
        for (String body : bodies) {
            preview(body, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid"));
        }
        assertThat(count("course_preview_event")).isEqualTo(before);
    }

    @Test
    void 손님에게_보이지_않는_가게가_있으면_404이고_기록하지_않는다() throws Exception {
        long requested = store("신청 중", "requested");
        int before = count("course_preview_event");

        preview(body("impression", a, b, requested, "weekend", "evening"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        preview(body("impression", a, b, c + 100_000, "weekend", "evening"), null).andExpect(status().isNotFound());
        assertThat(count("course_preview_event")).isEqualTo(before);
    }

    @Test
    void CSRF_토큰이_없으면_403_csrf_invalid() throws Exception {
        mvc.perform(post("/api/v1/course-previews").contentType(MediaType.APPLICATION_JSON)
                        .content(body("impression", a, b, c, "weekend", "evening")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    // ---- 준비 ----

    private ResultActions preview(String body, Cookie visitor) throws Exception {
        var request = post("/api/v1/course-previews").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (visitor != null) { // TestCsrf가 쿠키를 갈아 끼우므로 그 뒤에 방문자 쿠키를 더한다
            request.with(r -> {
                Cookie[] cookies = r.getCookies() == null ? new Cookie[0] : r.getCookies();
                Cookie[] withVisitor = java.util.Arrays.copyOf(cookies, cookies.length + 1);
                withVisitor[cookies.length] = visitor;
                r.setCookies(withVisitor);
                return r;
            });
        }
        return mvc.perform(request);
    }

    private static String body(String type, long first, long second, long third, String dayType, String timeBand) {
        return "{\"type\":\"" + type + "\",\"storeIds\":[\"" + first + "\",\"" + second + "\",\"" + third
                + "\"],\"dayType\":\"" + dayType + "\",\"timeBand\":\"" + timeBand + "\"}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long store(String name, String joinStatus) {
        jdbc.update("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (?, ?, 'cafe', 'center', ?)",
                region, name, joinStatus);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
