package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 지금 이 요청자가 쿠폰을 받을 수 있는지. 값은 docs/CONTRACTS.md 「3」 쿠폰 {@code downloadStatus}와 같다. */
public enum DownloadStatus {

    AVAILABLE("available"),
    LOGIN_REQUIRED("login_required"),
    WAITING("waiting"),
    APPLIED_UNUSED("applied_unused"),
    UNAVAILABLE("unavailable");

    private final String code;

    DownloadStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
