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
 * @param change 예약 변경(D8)이면 {@link #CHANGE_REQUESTED} · {@link #CHANGE_APPROVED} · {@link #CHANGE_REJECTED}, 아니면 null.
 *               변경 승인과 변경 거절은 둘 다 신청 → 확정이라 이 값으로만 구분된다(C14 알림이 씀)
 */
public record ReservationStatusChangedEvent(
        long reservationId,
        long storeId,
        long customerId,
        String fromStatus,
        String toStatus,
        String cancelReason,
        boolean byScheduler,
        String change) {

    /** 손님 변경 요청: 확정 → 신청 */
    public static final String CHANGE_REQUESTED = "requested";
    /** 사장님 변경 승인: 신청 → 확정(바꾼 값) */
    public static final String CHANGE_APPROVED = "approved";
    /** 변경 거절 · 변경 자동 거절: 신청 → 확정(원래 값) */
    public static final String CHANGE_REJECTED = "rejected";
}
