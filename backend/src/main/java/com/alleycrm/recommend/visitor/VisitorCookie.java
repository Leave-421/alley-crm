package com.alleycrm.recommend.visitor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 익명 방문자 쿠키 {@code alley_vid}(CONTRACTS_API 3.3). 값은 서버가 만든 UUID이고 90일 뒤 사라진다.
 * 기록할 때마다 수명을 늘리지 않는다. {@code Secure}는 세션 쿠키와 같은 설정을 따른다.
 */
@Component
public class VisitorCookie {

    public static final String NAME = "alley_vid";
    static final Duration MAX_AGE = Duration.ofDays(90);

    private final boolean secure;

    VisitorCookie(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.secure = secure;
    }

    /** 쿠키의 방문자 ID. 없거나 UUID가 아니면 빈 값. */
    Optional<UUID> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return parse(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    void write(HttpServletResponse response, UUID anonId) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(anonId.toString(), MAX_AGE).toString());
    }

    /** 브라우저에서 쿠키를 지운다(로그아웃). */
    void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    private static Optional<UUID> parse(String value) {
        if (value == null || value.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            // 서버가 만든 값이 아니다. 없는 것으로 보고 새로 발급한다(3.3 「다시 발급」).
            return Optional.empty();
        }
    }
}
