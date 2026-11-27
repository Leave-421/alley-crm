package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.AssociationCouponOfferResponse;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.service.AssociationCouponOfferService;
import com.alleycrm.customer.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원의 상인회 쿠폰 행사 카드 · 받기(E19, CONTRACTS_API 3.9). 회원 손님만 — 비로그인 401, 사장님 · 상인회 ·
 * 비회원 제한 세션 403(FR-CPN-13 #3). 손님은 세션으로 정하고 요청으로 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/association-coupon-events")
class AssociationCouponOfferController {

    private final AssociationCouponOfferService service;
    private final CustomerService customers;

    AssociationCouponOfferController(AssociationCouponOfferService service, CustomerService customers) {
        this.service = service;
        this.customers = customers;
    }

    @GetMapping("/{eventId}")
    AssociationCouponOfferResponse offer(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String eventId) {
        long customerId = member(me);
        return service.offer(parseEventId(eventId), customerId);
    }

    @PostMapping("/{eventId}/claim")
    @ResponseStatus(HttpStatus.CREATED)
    CouponResponse claim(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String eventId) {
        long customerId = member(me);
        return service.claim(parseEventId(eventId), customerId);
    }

    private long member(AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.CUSTOMER);
        return customers.identifyMember(me.id());
    }

    /** API의 ID는 문자열이다. 숫자가 아니면 그런 행사는 없다(404). */
    private static long parseEventId(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return Long.parseLong(value);
    }
}
