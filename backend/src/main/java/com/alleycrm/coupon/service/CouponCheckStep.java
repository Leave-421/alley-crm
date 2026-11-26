package com.alleycrm.coupon.service;

import com.alleycrm.reservation.request.ReservationRequestContext;
import com.alleycrm.reservation.request.ReservationRequestStep;
import org.springframework.stereotype.Component;

/**
 * 예약 신청 단계 60 쿠폰 확인(E13, CONTRACTS_API 3.5). 회원이 고른 쿠폰이 있으면 잠그고 확인만 한다.
 * 적용은 저장(80) 뒤 단계 90({@link CouponLinkStep})이 한다. 비회원이 보낸 쿠폰은 무시한다(FR-CPN-06 #3).
 */
@Component
class CouponCheckStep implements ReservationRequestStep {

    private final CouponApplyService service;

    CouponCheckStep(CouponApplyService service) {
        this.service = service;
    }

    @Override
    public int order() {
        return COUPON;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        if (!CouponLinkStep.hasMemberCoupon(ctx)) {
            return;
        }
        service.checkForRequest(ctx.getCustomerId(), ctx.getCouponId(), ctx.getStoreId(), ctx.getRequestedAt());
    }
}
