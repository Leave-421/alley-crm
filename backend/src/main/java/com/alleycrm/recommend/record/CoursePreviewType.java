package com.alleycrm.recommend.record;

import java.util.Arrays;
import java.util.Optional;

/** 오늘의 코스 기록 종류. 값은 docs/CONTRACTS.md 「3」 오늘의 코스 기록 {@code type}과 같다. */
public enum CoursePreviewType {

    IMPRESSION("impression"),
    CLICK("click");

    private final String code;

    CoursePreviewType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<CoursePreviewType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }
}
