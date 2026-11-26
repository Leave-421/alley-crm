package com.alleycrm.coupon.controller;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.CouponUseResponse;
import com.alleycrm.coupon.service.CouponStatusService;
import com.alleycrm.store.StoreService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사장님 쿠폰 사용 확인(E14, CONTRACTS_API 3.6, FR-CPN-08). 가게는 로그인한 사장님 계정의 승인된 가게로만 정한다.
 */
@RestController
class CouponUseController {

    private final CouponStatusService service;
    private final StoreService stores;

    CouponUseController(CouponStatusService service, StoreService stores) {
        this.service = service;
        this.stores = stores;
    }

    /** 사장님만(그 밖의 역할 · 승인 전 가게 403). 숫자가 아닌 예약 ID는 그런 예약이 없다(404). */
    @PostMapping("/api/v1/owner/reservations/{id}/coupon-use")
    CouponUseResponse confirmUse(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String id) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        long storeId = stores.requireApprovedOwnStore(me);
        if (!id.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return service.confirmUse(storeId, Long.parseLong(id));
    }
}
