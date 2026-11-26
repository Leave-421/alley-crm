package com.alleycrm.coupon.service;

import com.alleycrm.reservation.ReservationStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 예약 상태 변경(CONTRACTS_API 2 「상태 변경 이벤트」)에 따라 쿠폰 상태를 바꾼다. 상태를 바꾼 트랜잭션 안에서 돌아, 실패하면 상태
 * 변경도 되돌아간다.
 * <ul>
 *   <li>방문 전 취소(손님 취소 · 가게 거절 · 자동 거절 · 자동 취소) → 적용 쿠폰을 '적용 취소'(3.5, FR-CPN-05 #4)</li>
 *   <li>취소 → 방문(늦은 방문 처리, 11-25 (5)) → '적용 취소'된 쿠폰을 '예약에 적용됨'으로 되돌림(3.6)</li>
 * </ul>
 */
@Component
class CouponCancelListener {

    private static final String CANCELED = "canceled";
    private static final String VISITED = "visited";

    private final CouponApplyService applyService;
    private final CouponStatusService statusService;

    CouponCancelListener(CouponApplyService applyService, CouponStatusService statusService) {
        this.applyService = applyService;
        this.statusService = statusService;
    }

    @EventListener
    void on(ReservationStatusChangedEvent event) {
        if (CANCELED.equals(event.toStatus())) {
            applyService.cancelForReservation(event.reservationId());
        } else if (CANCELED.equals(event.fromStatus()) && VISITED.equals(event.toStatus())) {
            statusService.restoreForLateVisit(event.reservationId());
        }
    }
}
