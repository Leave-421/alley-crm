package com.alleycrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 비로그인 손님 경로(#222, CONTRACTS_API R-10 · V-2 · K-1 · T-1, CONTRACTS.md 「6」).
 * 보안 설정이 로그인 없이 통과시키는지만 본다. 각 API의 동작은 그 담당 테스트가 확인한다.
 * 없는 ID로 불러 401(로그인 필요) · 403(막힘)이 아닌 API 자체의 응답(200 · 400 · 404)이 오는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublicRoutesTest {

    static final String NO_SUCH_ID = "999999999";

    @Autowired
    MockMvc mvc;

    @Test
    void 비로그인_GET_경로는_로그인_없이_API까지_간다() throws Exception {
        assertReachesApi(get("/api/v1/today-courses").param("regionId", NO_SUCH_ID));
        assertReachesApi(get("/api/v1/recommendations").param("baseStoreId", NO_SUCH_ID));
        assertReachesApi(get("/api/v1/coupon-offers/{storeId}", NO_SUCH_ID));
    }

    @Test
    void 비로그인_POST_경로는_CSRF_토큰이_있으면_로그인_없이_API까지_간다() throws Exception {
        assertReachesApi(post("/api/v1/recommendation-exposures").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertReachesApi(post("/api/v1/store-clicks").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":\"" + NO_SUCH_ID + "\"}"));
    }

    @Test
    void 비로그인_POST_경로도_CSRF_토큰이_없으면_403() throws Exception {
        for (String path : new String[] {"/api/v1/recommendation-exposures", "/api/v1/store-clicks"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        }
    }

    @Test
    void 열지_않은_경로는_여전히_로그인이_필요하다() throws Exception {
        mvc.perform(post("/api/v1/coupons").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exposureId\":\"1\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/owner/reservation-settings")).andExpect(status().isUnauthorized());
    }

    private void assertReachesApi(RequestBuilder request) throws Exception {
        int status = mvc.perform(request).andReturn().getResponse().getStatus();
        assertThat(status).as("로그인 없이 API까지 가야 한다").isNotIn(401, 403);
    }
}
