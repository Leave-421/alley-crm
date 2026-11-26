package com.alleycrm.recommend.score;

import com.alleycrm.setting.SettingKey;

/** 공공데이터 기본값 점수의 항(05-policy 「다음 방문 추천」 2단계)과 가중치 설정값 키. */
public enum DefaultScoreTerm {

    /** B 업종의 매출 규모 */
    ATTRACTIVENESS(SettingKey.RECOMMEND_WEIGHT_ATTRACTIVENESS),
    /** A와 B가 가까울수록 높음(Huff 거리 감쇠) */
    DISTANCE(SettingKey.RECOMMEND_WEIGHT_DISTANCE),
    /** A 업종 주변에 B 업종이 우연보다 많이 붙어 있는지 */
    CLQ(SettingKey.RECOMMEND_WEIGHT_CLQ),
    /** 기준 시각의 요일 · 다음 시간대에 B 업종 매출이 몰리는지(팀 가정, 독립 가정 추정) */
    TIME_BAND(SettingKey.RECOMMEND_WEIGHT_TIME_BAND),
    /** 손님 성별 · 연령대에서 B 업종 매출 비중(독립 가정 추정). 모르면 뺀다 */
    DEMOGRAPHIC(SettingKey.RECOMMEND_WEIGHT_DEMOGRAPHIC);

    private final SettingKey weightKey;

    DefaultScoreTerm(SettingKey weightKey) {
        this.weightKey = weightKey;
    }

    public SettingKey weightKey() {
        return weightKey;
    }
}
