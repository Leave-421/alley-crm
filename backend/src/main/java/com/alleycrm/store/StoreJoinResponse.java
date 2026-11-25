package com.alleycrm.store;

import java.util.List;

/** 계약 `StoreJoin`. ID는 문자열, 시각은 HH:mm. */
record StoreJoinResponse(String storeId, String regionId, String name, String category, String alley,
        String openTime, String closeTime, List<String> closedDays, String joinStatus) {

    static StoreJoinResponse of(Store s) {
        return new StoreJoinResponse(String.valueOf(s.getId()), String.valueOf(s.getRegionId()), s.getName(),
                s.getCategory(), s.getAlley(),
                StoreResponse.hhmm(s.getOpenTime()), StoreResponse.hhmm(s.getCloseTime()),
                StoreResponse.closedDays(s.getClosedDays()), s.getJoinStatus());
    }
}
