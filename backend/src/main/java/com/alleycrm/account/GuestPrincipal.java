package com.alleycrm.account;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 비회원 예약 조회 제한 세션(C13, FR-AUTH-03). 계정이 없고, 그 전화번호의 비회원 예약만 다룬다.
 * 허용 경로는 SecurityConfig가 정하고, 그 밖의 로그인 필요 API는 403 forbidden_role.
 */
public record GuestPrincipal(String phone) implements AuthenticatedPrincipal, Serializable {

    /** 세션 권한 이름(ROLE_guest). 계정 역할(AccountRole)과 겹치지 않는다. */
    public static final String ROLE = "guest";

    /** 계정 이름이 없다. 예약 신청(D2)은 이름이 없으면 비회원 신청으로 본다. */
    @Override
    public String getName() {
        return null;
    }

    /** 로그에 전화번호가 남지 않게 한다. */
    @Override
    public String toString() {
        return "GuestPrincipal[phone=***]";
    }
}
