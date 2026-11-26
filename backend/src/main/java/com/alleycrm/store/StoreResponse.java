package com.alleycrm.store;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** 계약 `Store`. ID는 문자열, 시각은 HH:mm, 실제 좌표는 검증된 경우만. */
record StoreResponse(String id, String regionId, String name, String category, String alley,
        BigDecimal mapX, BigDecimal mapY, BigDecimal realLat, BigDecimal realLng, String address,
        String openTime, String closeTime, List<String> closedDays,
        String businessStatus, boolean reservationEnabled, boolean reservable, String joinStatus) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    static StoreResponse of(Store s) {
        return new StoreResponse(String.valueOf(s.getId()), String.valueOf(s.getRegionId()), s.getName(),
                s.getCategory(), s.getAlley(), s.getMapX(), s.getMapY(),
                s.isRealVerified() ? s.getRealLat() : null, s.isRealVerified() ? s.getRealLng() : null,
                s.getAddress(), hhmm(s.getOpenTime()), hhmm(s.getCloseTime()), closedDays(s.getClosedDays()),
                s.getBusinessStatus(), s.isReservationEnabled(), s.isReservable(), s.getJoinStatus());
    }

    static String hhmm(LocalTime t) {
        return t == null ? null : t.format(HH_MM);
    }

    static List<String> closedDays(String value) {
        return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
    }
}
