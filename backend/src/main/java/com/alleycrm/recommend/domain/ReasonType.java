package com.alleycrm.recommend.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 추천 이유 종류. 값은 docs/CONTRACTS.md 「3」 추천 {@code reasonType}과 같다. */
public enum ReasonType {

    VISIT_DATA("visit_data"),
    COLOCATION("colocation"),
    TIME_BAND("time_band"),
    ATTRACTIVENESS("attractiveness"),
    DEMOGRAPHIC("demographic"),
    DISTANCE("distance");

    private final String code;

    ReasonType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
