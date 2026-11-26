package com.alleycrm.recommend.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 추천 배지. 값은 docs/CONTRACTS.md 「3」 {@code badge}와 같다. */
public enum RecommendationBadge {

    VISIT_DATA("visit_data"),
    LOCAL_POPULAR("local_popular");

    private final String code;

    RecommendationBadge(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
