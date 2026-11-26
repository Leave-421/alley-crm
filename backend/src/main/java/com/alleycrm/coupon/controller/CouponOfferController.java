package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.CouponDownloadRequest;
import com.alleycrm.coupon.dto.CouponOfferResponse;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.service.CouponOfferService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 손님의 추천 쿠폰 상세 · 다운로드(E12, CONTRACTS_API 3.4). 회원은 로그인한 계정으로만 정하고 손님 ID를 받지 않는다.
 *
 * <p>쿠폰 상세를 로그인 없이 여는 보안 설정은 C(#24, 3.4 K-1)가 더한다. 그 전에는 로그인하지 않은 요청이
 * 보안 설정에서 401로 끝나고, 열린 뒤에는 {@code login_required}로 응답한다.
 */
@RestController
class CouponOfferController {

    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";

    private final CouponOfferService service;

    CouponOfferController(CouponOfferService service) {
        this.service = service;
    }

    /** 누구나. 사장님 · 상인회는 403. 숫자가 아닌 가게 ID는 그런 가게가 없다(404). */
    @GetMapping("/api/v1/coupon-offers/{storeId}")
    CouponOfferResponse offer(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String storeId) {
        Long memberAccountId = null;
        if (me != null) {
            me.requireRole(AccountRole.CUSTOMER);
            memberAccountId = me.id();
        }
        if (!storeId.matches(ID_PATTERN)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return service.offer(Long.parseLong(storeId), memberAccountId);
    }

    /** 회원 손님만. 비로그인 401, 사장님 · 상인회 403. */
    @PostMapping("/api/v1/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    CouponResponse download(@AuthenticationPrincipal AccountPrincipal me,
            @RequestBody(required = false) CouponDownloadRequest body) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.CUSTOMER);
        if (body == null || body.exposureId() == null || !body.exposureId().matches(ID_PATTERN)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return service.download(me.id(), Long.parseLong(body.exposureId()));
    }
}
