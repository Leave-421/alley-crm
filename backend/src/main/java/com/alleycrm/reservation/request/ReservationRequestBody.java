package com.alleycrm.reservation.request;

import java.time.Instant;

/** POST /api/v1/reservations 요청 본문(계약 그대로). 값 검사는 입력 검증 단계(10)가 한다. */
public record ReservationRequestBody(
        String storeId,
        Instant startAt,
        Integer partySize,
        String requestNote,
        Boolean shareConsent,
        Guest guest,
        String couponId,
        String courseRootId,
        String courseProposalId) {

    public record Guest(String name, String phone, String gender, String ageGroup) {
    }
}
