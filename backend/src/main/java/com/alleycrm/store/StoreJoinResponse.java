package com.alleycrm.store;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** 계약 `StoreJoin`. ID는 문자열, 시각은 HH:mm. */
record StoreJoinResponse(String storeId, String regionId, String name, String category, String alley,
        String openTime, String closeTime, List<String> closedDays, String joinStatus) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    static StoreJoinResponse of(Store s) {
        return new StoreJoinResponse(String.valueOf(s.getId()), String.valueOf(s.getRegionId()), s.getName(),
                s.getCategory(), s.getAlley(),
                s.getOpenTime() == null ? null : s.getOpenTime().format(HH_MM),
                s.getCloseTime() == null ? null : s.getCloseTime().format(HH_MM),
                s.getClosedDays().isEmpty() ? List.of() : Arrays.asList(s.getClosedDays().split(",")),
                s.getJoinStatus());
    }
}
