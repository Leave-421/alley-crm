package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** 상인회 쿠폰을 쓸 수 있는 가게. 값은 docs/CONTRACTS.md 「3」 {@code eligibleStoreScope}와 같다(11-25 (5)). */
public enum EligibleStoreScope {

    /** 그 권역의 참여 가게 전부(쓸 때 기준). */
    ALL("all"),
    /** 상인회가 고른 가게(association_coupon_event_store). */
    SELECTED("selected");

    private final String code;

    EligibleStoreScope(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<EligibleStoreScope> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
