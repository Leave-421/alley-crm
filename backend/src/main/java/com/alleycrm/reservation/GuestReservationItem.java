package com.alleycrm.reservation;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 비회원 예약 목록(C13 `GET /api/v1/guest/reservations`) 한 건: {@link ReservationResponse}와 같은 칸 + 취소 안내.
 * 회원 내 예약(D13)의 `cancelable` · `refundIfCanceled`와 같은 뜻이다(D6 규칙).
 *
 * @param cancelable 지금 취소할 수 있는지(신청 · 확정이고 가게 취소 마감 전)
 * @param refundIfCanceled 지금 취소하면 돌려받는 예약금(취소할 수 없으면 null)
 */
public record GuestReservationItem(
        @JsonUnwrapped ReservationResponse reservation,
        boolean cancelable,
        Integer refundIfCanceled) {
}
