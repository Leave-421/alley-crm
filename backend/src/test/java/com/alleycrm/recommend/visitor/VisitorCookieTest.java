package com.alleycrm.recommend.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 익명 방문자 쿠키 alley_vid (CONTRACTS_API 3.3). DB 없이 돈다. */
class VisitorCookieTest {

    private final VisitorCookie cookie = new VisitorCookie(false);

    @Test
    void 서버가_만든_UUID만_읽는다() {
        UUID id = UUID.randomUUID();

        assertThat(cookie.read(request(id.toString()))).contains(id);
        assertThat(cookie.read(request("not-a-uuid"))).isEmpty();
        assertThat(cookie.read(request(""))).isEmpty();
        assertThat(cookie.read(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void 쿠키는_90일_HttpOnly_SameSite_Lax() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        UUID id = UUID.randomUUID();

        cookie.write(response, id);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header)
                .startsWith("alley_vid=" + id)
                .contains("Path=/", "Max-Age=7776000", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
    }

    @Test
    void HTTPS_설정이면_Secure() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new VisitorCookie(true).write(response, UUID.randomUUID());

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Secure");
    }

    @Test
    void 지우면_Max_Age_0() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("alley_vid=;").contains("Max-Age=0");
    }

    private static MockHttpServletRequest request(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(VisitorCookie.NAME, value));
        return request;
    }
}
