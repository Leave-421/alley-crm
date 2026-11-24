package com.alleycrm.setting;

/**
 * 설정값 키와 기본값(NFR-04). 기준: 기획 저장소 docs/05-policy.md 「설정값」 · 「추천 설정값」.
 * 코드에 숫자를 박지 않고 {@link SettingService}로 읽는다. 권역별로 바꾼 값은 setting 테이블에 둔다.
 * 키를 더하거나 기본값을 바꾸는 것은 계약 변경이다(docs/CONTRACTS.md 「3」 설정값 표와 같은 PR에서).
 */
public enum SettingKey {

    /** 소개 수수료율. 수수료를 만들 때의 값을 수수료 기록에 저장하므로 바꿔도 이미 생긴 수수료는 그대로다. */
    REFERRAL_FEE_RATE("referral_fee_rate", "0.04"),
    /** 재방문 기간(일). 재방문율 · 이탈 위험 등급. */
    REVISIT_DAYS("revisit_days", "60"),
    /** 하루 전이 지난 뒤 취소의 환불 비율 기본값. 가게가 따로 정하면(store.late_cancel_refund_rate) 그 값을 쓴다. */
    LATE_CANCEL_REFUND_RATE("late_cancel_refund_rate", "0.5"),
    /** 비회원 정보 보관 기간(개월, 마지막 방문 후). */
    GUEST_RETENTION_MONTHS("guest_retention_months", "6"),
    /** 방문 기록 없는 비회원 보관 기간(일, 마지막 예약 취소 후). */
    GUEST_NO_VISIT_RETENTION_DAYS("guest_no_visit_retention_days", "30"),

    /** 추천 기본값 가중치: 매력도 · 거리 · CLQ · 시간대 · 손님층. */
    RECOMMEND_WEIGHT_ATTRACTIVENESS("recommend_weight_attractiveness", "0.25"),
    RECOMMEND_WEIGHT_DISTANCE("recommend_weight_distance", "0.25"),
    RECOMMEND_WEIGHT_CLQ("recommend_weight_clq", "0.25"),
    RECOMMEND_WEIGHT_TIME_BAND("recommend_weight_time_band", "0.125"),
    RECOMMEND_WEIGHT_DEMOGRAPHIC("recommend_weight_demographic", "0.125"),
    /** 평활 상수 k. */
    RECOMMEND_SMOOTHING_K("recommend_smoothing_k", "10"),
    /** 성별·연령대 집단 비율 g. */
    RECOMMEND_GROUP_RATIO("recommend_group_ratio", "0.3"),
    /** 실제 기록 점수를 쓰는 최소 고유 손님 수(5명 미만 숨김과 같은 기준). */
    RECOMMEND_MIN_UNIQUE_CUSTOMERS("recommend_min_unique_customers", "5"),
    /** 거리 지수 β. 기획 미결이라 임시 기본값이다(05-policy). 확정되면 값만 바꾼다. */
    RECOMMEND_DISTANCE_EXPONENT("recommend_distance_exponent", "1.0"),
    /** 추천 개수. */
    RECOMMEND_COUNT("recommend_count", "3");

    private final String key;
    private final String defaultValue;

    SettingKey(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
