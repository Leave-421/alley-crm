package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** 쿠폰 상태. 값은 docs/CONTRACTS.md 「3」 {@code coupon.status}와 같다. */
public enum CouponStatus {

    DOWNLOADED("downloaded"),
    APPLIED("applied"),
    USED("used"),
    EXPIRED("expired"),
    APPLY_CANCELED("apply_canceled");

    private final String code;

    CouponStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<CouponStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
