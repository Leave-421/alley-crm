package com.alleycrm.reservation;

/**
 * 예약 상태가 바뀔 때 내보내는 이벤트(D3 · D4 · D6 · D11이 같이 씀).
 * 상태를 바꾼 트랜잭션 안에서 나간다. 받는 쪽:
 * <ul>
 *   <li>같은 트랜잭션에서 같이 바뀌어야 하는 일(예약금 환불 D10/D6, 쿠폰 적용 취소 E)은
 *       {@code @EventListener} — 실패하면 상태 변경도 되돌아간다</li>
 *   <li>커밋 뒤에만 하는 일(알림 C14 · C15)은 {@code @TransactionalEventListener}</li>
 * </ul>
 * 환불 비율은 받는 쪽이 사유로 정한다: store_rejected 100%, auto_canceled 0%(05-policy 「예약 상태」).
 *
 * @param cancelReason 취소일 때만 값(CancelReason), 아니면 null
 * @param byScheduler 자동 작업(자동 거절 · 자동 취소)이면 true
 */
public record ReservationStatusChangedEvent(
        long reservationId,
        long storeId,
        long customerId,
        String fromStatus,
        String toStatus,
        String cancelReason,
        boolean byScheduler) {
}
