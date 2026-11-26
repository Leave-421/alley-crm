package com.alleycrm.coupon.service;

import com.alleycrm.reservation.VisitProcessedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * '방문 처리됨'(D11)을 받아 적용된 쿠폰의 실제 할인액을 남긴다(CONTRACTS_API 3.6 「방문 할인」). 방문 처리한 트랜잭션 안에서
 * 돌아, 실패하면 방문 처리도 되돌아간다. 쿠폰은 '예약에 적용됨' 그대로다(FR-CPN-08).
 */
@Component
class CouponVisitListener {

    private final CouponVisitService service;

    CouponVisitListener(CouponVisitService service) {
        this.service = service;
    }

    @EventListener
    void on(VisitProcessedEvent event) {
        service.recordDiscount(event.reservationId(), event.visitAmount());
    }
}
