package com.alleycrm.coupon.service;

import com.alleycrm.reservation.request.ReservationRequestContext;
import com.alleycrm.reservation.request.ReservationRequestStep;
import org.springframework.stereotype.Component;

/**
 * 예약 신청 단계 90 쿠폰 예약 연결(E13, CONTRACTS_API 3.5). 예약 ID는 저장(80) 뒤에 생겨서 저장 다음에 둔다.
 * 단계 60에서 확인 · 잠근 쿠폰을 그 예약에 적용하고 응답의 {@code coupon}을 채운다. 같은 트랜잭션이다.
 */
@Component
class CouponLinkStep implements ReservationRequestStep {

    private final CouponApplyService service;

    CouponLinkStep(CouponApplyService service) {
        this.service = service;
    }

    @Override
    public int order() {
        return COUPON_LINK;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        if (!hasMemberCoupon(ctx)) {
            return;
        }
        ctx.setAppliedCoupon(service.applyToReservation(
                ctx.getCustomerId(), ctx.getCouponId(), ctx.getReservation().getId()));
    }

    /** 회원(로그인한 손님, 단계 20이 역할을 확인함)이 쿠폰을 골랐는지. 비회원 쿠폰은 무시한다. */
    static boolean hasMemberCoupon(ReservationRequestContext ctx) {
        return ctx.getCouponId() != null && ctx.loggedIn() && ctx.getCustomerId() != null;
    }
}
