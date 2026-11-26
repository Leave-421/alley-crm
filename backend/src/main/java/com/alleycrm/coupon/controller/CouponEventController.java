package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.BenefitTypesResponse;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;
import com.alleycrm.coupon.dto.CouponEventResponse;
import com.alleycrm.coupon.dto.CurrentCouponEventResponse;
import com.alleycrm.coupon.service.CouponEventService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사장님 쿠폰 행사 설정(E11, CONTRACTS_API 3.2). 가게는 경로로 받지 않고 로그인한 사장님 계정의 가게로 정한다.
 * 고치는 API는 없다(확정 뒤 변경 불가, 11-25). {@code PUT} · {@code PATCH}는 공통 규칙대로 404.
 */
@RestController
@RequestMapping("/api/v1/coupon-events")
class CouponEventController {

    private final CouponEventService service;

    CouponEventController(CouponEventService service) {
        this.service = service;
    }

    @GetMapping("/benefit-types")
    BenefitTypesResponse benefitTypes(@AuthenticationPrincipal AccountPrincipal me) {
        ownerStoreId(me);
        return service.benefitTypes();
    }

    @GetMapping("/mine")
    CurrentCouponEventResponse mine(@AuthenticationPrincipal AccountPrincipal me) {
        return service.current(ownerStoreId(me));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CouponEventResponse create(@AuthenticationPrincipal AccountPrincipal me,
            @RequestBody CouponEventCreateRequest body) {
        return service.create(ownerStoreId(me), body);
    }

    @PostMapping("/{eventId}/stop")
    CouponEventResponse stop(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String eventId) {
        long storeId = ownerStoreId(me);
        return service.stop(storeId, parseEventId(eventId));
    }

    /** 사장님만. 손님 · 상인회는 403. 가게가 없는 사장님 계정도 가게 기능을 쓸 수 없어 403. */
    private static long ownerStoreId(AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.OWNER);
        if (me.storeId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return me.storeId();
    }

    /** API의 ID는 문자열이다. 숫자가 아니면 그런 행사는 없다(404). */
    private static long parseEventId(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return Long.parseLong(value);
    }
}
