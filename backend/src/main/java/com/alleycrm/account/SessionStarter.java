package com.alleycrm.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/** 로그인(C2) · 비회원 예약 조회(C13)가 세션을 시작하는 한 곳. 세션 ID를 새로 발급해 세션 고정을 막는다. */
final class SessionStarter {

    private static final SecurityContextRepository CONTEXTS = new HttpSessionSecurityContextRepository();

    private SessionStarter() {
    }

    static void start(Object principal, String role, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // 세션이 없으면 saveContext가 새로 만든다
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
        CONTEXTS.saveContext(context, request, response);
    }
}
