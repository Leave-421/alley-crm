package com.alleycrm.account;

import com.alleycrm.customer.CustomerService;
import com.alleycrm.recommend.visitor.VisitorService;
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
    private final CustomerService customerService;
    private final VisitorService visitors;

    AuthController(AccountService accountService, CustomerService customerService, VisitorService visitors) {
        this.accountService = accountService;
        this.customerService = customerService;
        this.visitors = visitors;
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
        // 로그인 전 이 브라우저의 탐색 기록을 회원 손님에 잇는다(3.3 「방문자를 손님에 잇기」, FR-CRS-07 #2). 사장님 · 상인회는 손님이 없다
        if (AccountRole.CUSTOMER.equals(me.role())) {
            customerService.findMemberCustomerId(me.id()).ifPresent(visitors::linkCurrentBrowser);
        }
        return AccountResponse.from(me);
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        visitors.forgetCurrentBrowser(); // 이후 탐색은 새 익명 방문자로(3.3)
    }

    @GetMapping("/api/v1/auth/me")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal me) {
        return AccountResponse.from(me);
    }
}
