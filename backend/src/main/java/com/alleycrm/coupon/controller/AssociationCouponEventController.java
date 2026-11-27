package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.AssociationCouponEventCreateRequest;
import com.alleycrm.coupon.dto.AssociationCouponEventResponse;
import com.alleycrm.coupon.dto.AssociationCouponEventsResponse;
import com.alleycrm.coupon.service.AssociationCouponEventService;
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
 * 상인회 쿠폰 행사(E18, CONTRACTS_API 3.8). 권역은 경로로 받지 않고 로그인한 상인회 계정의 권역으로 정한다.
 * 고치는 API는 없다(확정 뒤 변경 불가). {@code PUT} · {@code PATCH}는 공통 규칙대로 404.
 */
@RestController
@RequestMapping("/api/v1/association/coupon-events")
class AssociationCouponEventController {

    private final AssociationCouponEventService service;

    AssociationCouponEventController(AssociationCouponEventService service) {
        this.service = service;
    }

    @GetMapping
    AssociationCouponEventsResponse list(@AuthenticationPrincipal AccountPrincipal me) {
        return service.list(associationRegionId(me));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AssociationCouponEventResponse create(@AuthenticationPrincipal AccountPrincipal me,
            @RequestBody(required = false) AssociationCouponEventCreateRequest body) {
        return service.create(associationRegionId(me), body);
    }

    @PostMapping("/{eventId}/stop")
    AssociationCouponEventResponse stop(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String eventId) {
        long regionId = associationRegionId(me);
        return service.stop(regionId, parseEventId(eventId));
    }

    /** 상인회만. 손님 · 사장님은 403. 권역이 없는 상인회 계정도 403. */
    private static long associationRegionId(AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.ASSOCIATION);
        if (me.regionId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return me.regionId();
    }

    /** API의 ID는 문자열이다. 숫자가 아니면 그런 행사는 없다(404). */
    private static long parseEventId(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return Long.parseLong(value);
    }
}
