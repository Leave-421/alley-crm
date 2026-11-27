package com.alleycrm.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입 · 로그인 · 로그아웃 · 내 계정. 계약: CONTRACTS_API 1절 (C2). */
@RestController
class AuthController {

    private final AccountService accountService;

    AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/v1/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse signup(@Valid @RequestBody SignupRequest body) {
        return AccountResponse.from(accountService.signupCustomer(
                body.loginId(), body.password(), body.name(), body.gender(), body.ageGroup()));
    }

    @PostMapping("/api/v1/auth/login")
    AccountResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AccountPrincipal me = accountService.authenticate(body.loginId(), body.password());
        SessionStarter.start(me, me.role(), request, response);
        return AccountResponse.from(me);
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    @GetMapping("/api/v1/auth/me")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal me) {
        return AccountResponse.from(me);
    }
}
