package com.alleycrm.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 상태 변경 요청 테스트용 CSRF: 프론트와 같은 흐름으로 GET /api/v1/csrf의 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다.
 * spring-security-test의 csrf()는 공유 컨텍스트의 CsrfFilter 저장소를 바꿔 다른 테스트(ApiBaseTest)를 깨뜨리므로 쓰지 않는다.
 */
public final class TestCsrf {

    private TestCsrf() {
    }

    public static RequestPostProcessor csrf(MockMvc mvc) throws Exception {
        Cookie token = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request -> {
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }
}
