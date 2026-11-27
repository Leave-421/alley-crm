package com.alleycrm.reservation;

import java.time.Instant;

/**
 * 손님이 지연을 입력함(D14, FR-RSV-10 #2). 예약 시각은 그대로다.
 * 알림(C14)은 {@code @TransactionalEventListener}로 받아 그 가게 사장님에게 `delay_reported` 알림을 만든다.
 */
public record DelayReportedEvent(long reservationId, long storeId, long customerId, Instant startAt, Instant expectedArrivalAt) {
}
