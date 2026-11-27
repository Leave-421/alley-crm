package com.alleycrm.config;

import com.alleycrm.account.GuestPrincipal;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 보안 뼈대: 서버 세션 + CSRF, 기본은 모두 로그인 필요.
 * 로그인 · 역할 · 더 열어 둘 경로는 C2 · C3 이슈가 이 설정에 더한다. 전역 permitAll()을 두지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // SPA용 CSRF: XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 보낸다.
                .csrf(csrf -> csrf.spa())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 로그아웃은 AuthController의 POST /api/v1/auth/logout
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        // 가게 가입 신청은 가게 · 사장님 계정을 같이 만든다(C5)
                        .requestMatchers(HttpMethod.POST, "/api/v1/store-joins").permitAll()
                        // 참여 가게 목록은 지도에서 로그인 없이 본다(C6)
                        .requestMatchers(HttpMethod.GET, "/api/v1/stores").permitAll()
                        // 권역 목록(#214): 가입 신청 · 손님 지도가 권역 ID를 얻는다
                        .requestMatchers(HttpMethod.GET, "/api/v1/regions").permitAll()
                        // 예약 신청은 비회원도 한다(D2). 회원 · 역할 판단은 신청 단계 20(C4)
                        .requestMatchers(HttpMethod.POST, "/api/v1/reservations").permitAll()
                        // 예약 가능 시간 조회(D5)도 비회원이 본다
                        .requestMatchers(HttpMethod.GET, "/api/v1/stores/*/available-slots").permitAll()
                        // 비로그인 손님의 추천 · 탐색 기록 · 쿠폰 상세 · 오늘의 코스(#222, CONTRACTS_API R-10 · V-2 · K-1 · T-1)
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommendations", "/api/v1/today-courses",
                                "/api/v1/coupon-offers/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/recommendation-exposures", "/api/v1/store-clicks").permitAll()
                        // 비회원 예약 조회(C13): 맞으면 그 전화번호의 예약만 다루는 제한 세션을 받는다
                        .requestMatchers(HttpMethod.POST, "/api/v1/guest/lookup").permitAll()
                        .requestMatchers("/api/v1/guest/**").hasRole(GuestPrincipal.ROLE)
                        // 계정과 비회원 제한 세션이 같이 쓰는 경로. 받는 쪽은 세션으로 정한다
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications/*/read", "/api/v1/auth/logout").authenticated()
                        // 그 밖은 로그인한 계정만. 비회원 제한 세션은 403 forbidden_role
                        .anyRequest().access((authentication, context) -> new AuthorizationDecision(
                                isAccountSession(authentication.get()))))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, e instanceof CsrfException
                                        ? ErrorCode.CSRF_INVALID
                                        : ErrorCode.FORBIDDEN_ROLE)));
        return http.build();
    }

    /** 로그인했고 비회원 제한 세션이 아님. 익명이면 거절 → 401(진입점), 비회원 세션이면 거절 → 403. */
    private static boolean isAccountSession(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !(authentication.getPrincipal() instanceof GuestPrincipal);
    }

    /** 비밀번호 해시(bcrypt, {bcrypt} 접두어). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ErrorResponse.json(code));
    }
}
