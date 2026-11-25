package com.alleycrm.reservation.request;

/**
 * 예약 신청이 저장된 뒤 내보내는 이벤트. 알림(C14 · C15)은
 * {@code @TransactionalEventListener}(기본 AFTER_COMMIT)로 받아 커밋 뒤에만 보낸다.
 */
public record ReservationRequestedEvent(long reservationId, long storeId, long customerId) {
}
