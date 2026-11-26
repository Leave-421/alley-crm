package com.alleycrm.seed;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 시드 파일을 읽을 때 쓰는 소속 골목 3개(06-data STORE alley, 결정 11-24 (4)). 한 권역 안의 표시 구역이고 권역 선택이 아니다.
 * 값은 계약 {@code CONTRACTS.md} 「3」 {@code store.alley}(D1 #12)와 같다. 공통 Java enum이 생기면 그것으로 바꾸고 이 파일은 지운다.
 */
public enum SeedAlley {

    FRONT_GATE("front_gate", "정문 앞"),
    CENTER("center", "중앙"),
    BACK_STREET("back_street", "뒷길");

    private final String code;
    private final String label;

    SeedAlley(String code, String label) {
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
