package com.alleycrm.coupon.service;

import com.alleycrm.reservation.ReservationStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 예약이 방문 전에 취소되면(손님 취소 · 가게 거절 · 자동 거절 · 자동 취소) 적용 쿠폰을 '적용 취소'로 바꾼다
 * (CONTRACTS_API 2 「상태 변경 이벤트」 · 3.5, FR-CPN-05 #4). 상태를 바꾼 트랜잭션 안에서 돌아, 실패하면 상태 변경도 되돌아간다.
 */
@Component
class CouponCancelListener {

    private static final String CANCELED = "canceled";

    private final CouponApplyService service;

    CouponCancelListener(CouponApplyService service) {
        this.service = service;
    }

    @EventListener
    void on(ReservationStatusChangedEvent event) {
        if (CANCELED.equals(event.toStatus())) {
            service.cancelForReservation(event.reservationId());
        }
    }
}
