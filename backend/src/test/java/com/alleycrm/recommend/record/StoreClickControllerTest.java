package com.alleycrm.recommend.record;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/v1/store-clicks} (CONTRACTS_API 3.3).
 * 로그인 없이 여는 것은 C의 보안 설정(3.3 V-2) 뒤라, 여기서는 로그인한 요청으로 응답 · 검사를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@WithMockUser
class StoreClickControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    long store;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO region (name) VALUES ('클릭 권역')");
        long region = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (?, 'A', 'cafe', 'center', 'approved')", region);
        store = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 프론트와 같은 흐름: GET /api/v1/csrf의 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다.
     * spring-security-test의 csrf()는 공유 컨텍스트의 CSRF 저장소를 바꿔 다른 테스트를 깨뜨릴 수 있어 쓰지 않는다(AuthApiTest와 같음).
     */
    private RequestPostProcessor csrf() throws Exception {
        Cookie token = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request -> {
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    @Test
    void 기록하면_204이고_방문자_쿠키가_실린다() throws Exception {
        mvc.perform(post("/api/v1/store-clicks").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"" + store + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        Matchers.hasItem(Matchers.startsWith("alley_vid="))));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM store_click WHERE store_id = ?", Integer.class, store)).isEqualTo(1);
    }

    @Test
    void storeId가_숫자가_아니거나_없으면_400_invalid() throws Exception {
        for (String body : new String[] {"{\"storeId\": \"abc\"}", "{\"storeId\": \"0\"}", "{}", ""}) {
            mvc.perform(post("/api/v1/store-clicks").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void 없는_가게는_404_not_found() throws Exception {
        mvc.perform(post("/api/v1/store-clicks").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"" + (store + 1000) + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void CSRF_토큰이_없으면_403_csrf_invalid() throws Exception {
        mvc.perform(post("/api/v1/store-clicks")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"" + store + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }
}
