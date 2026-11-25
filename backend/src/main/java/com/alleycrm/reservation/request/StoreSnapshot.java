package com.alleycrm.reservation.request;

import java.time.LocalTime;

/** 신청을 처리하는 동안 읽은 가게 설정(예약 가능 확인 단계가 채움). 다른 단계는 읽기만 한다. */
public record StoreSnapshot(
        long id,
        long regionId,
        String joinStatus,
        String businessStatus,
        boolean reservationEnabled,
        LocalTime openTime,
        LocalTime closeTime,
        String closedDays,
        int slotMinutes,
        int capacityPerSlot,
        int bookingWindowDays,
        int depositPerPerson,
        Integer maxPartySize) {
}
