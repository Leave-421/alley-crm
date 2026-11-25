package com.alleycrm.seed;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 시드 파일을 읽을 때 쓰는 가게 업종 8개. 값은 계약 {@code CONTRACTS.md} 「3」 {@code store.category}(D1 #12)와 같다.
 * 공통 Java enum이 생기면 그것으로 바꾸고 이 파일은 지운다. 업종 구성(가게 수)은 03-requirements Q-25 잠정값이다.
 */
public enum SeedCategory {

    RESTAURANT("restaurant", "음식점"),
    CAFE("cafe", "카페"),
    DESSERT("dessert", "디저트"),
    PUB("pub", "주점"),
    WORKSHOP("workshop", "공방"),
    BOOKSTORE("bookstore", "서점"),
    GIFT_SHOP("gift_shop", "소품샵"),
    STATIONERY("stationery", "문구점");

    private final String code;
    private final String label;

    SeedCategory(String code, String label) {
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
}
