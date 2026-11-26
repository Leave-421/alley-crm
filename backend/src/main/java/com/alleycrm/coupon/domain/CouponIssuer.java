package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** 쿠폰 발급 주체. 값은 docs/CONTRACTS.md 「3」 {@code coupon.issuerKind}와 같다(11-25 (5)). */
public enum CouponIssuer {

    /** 가게 쿠폰: 쿠폰 비용은 가게가 낸다 */
    STORE("store"),
    /** 상인회 쿠폰: 쿠폰 비용은 공동 기금이 낸다 */
    ASSOCIATION("association");

    private final String code;

    CouponIssuer(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<CouponIssuer> fromCode(String code) {
        return Arrays.stream(values()).filter(issuer -> issuer.code.equals(code)).findFirst();
    }
}
