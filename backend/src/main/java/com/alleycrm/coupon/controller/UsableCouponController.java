package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.UsableCouponsResponse;
import com.alleycrm.coupon.service.CouponApplyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 화면의 사용 가능한 쿠폰(E13, CONTRACTS_API 3.5). 회원은 로그인한 계정으로만 정하고 손님 ID를 받지 않는다.
 */
@RestController
class UsableCouponController {

    private final CouponApplyService service;

    UsableCouponController(CouponApplyService service) {
        this.service = service;
    }

    /** 회원 손님만. 비로그인 401, 그 밖의 역할 403. storeId가 없거나 숫자가 아니면 400. */
    @GetMapping("/api/v1/coupons/usable")
    UsableCouponsResponse usable(@AuthenticationPrincipal AccountPrincipal me,
            @RequestParam(required = false) String storeId) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.CUSTOMER);
        if (storeId == null || !storeId.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return service.usable(me.id(), Long.parseLong(storeId));
    }
}
