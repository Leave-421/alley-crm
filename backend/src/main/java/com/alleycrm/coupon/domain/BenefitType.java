package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** 쿠폰 혜택 유형. 값은 docs/CONTRACTS.md 「3」 {@code benefitType}과 같다(11-25 정률 할인 추가). */
public enum BenefitType {

    FIXED_DISCOUNT("fixed_discount", "정액 할인"),
    PERCENT_DISCOUNT("percent_discount", "정률 할인"),
    SERVICE("service", "서비스 제공");

    private final String code;
    private final String label;

    BenefitType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<BenefitType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }
}
