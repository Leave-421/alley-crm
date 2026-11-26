package com.alleycrm.recommend.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 공공데이터 통계 종류(표 public_stat {@code kind}). 값은 docs/CONTRACTS.md 「3」 {@code publicStat.kind}와 같다.
 * 저장하는 값은 모두 관측값이다: 같은 묶음 안 비중이거나 상가 좌표로 센 CLQ. 교차값(요일 × 시간대, 성별 × 연령대)은
 * 저장하지 않고 추천 계산(E5) 때 비중을 곱해 추정한다(결정 2026-11-26 P-1).
 */
public enum PublicStatKind {

    /** 매력도: 참고 상권 우리 업종 매출 합에서 그 업종 비중. {@code category_to} */
    ATTRACTIVENESS("attractiveness"),
    /** 업종 동반 입지: A 주변 B가 우연 대비 몇 배인지. {@code category_from} → {@code category_to} */
    CLQ("clq"),
    /** 그 업종 매출의 요일 비중. {@code slot} = mon ~ sun */
    DAY_SHARE("day_share"),
    /** 그 업종 매출의 시간대 비중. {@code slot} = {@link TimeBand} */
    TIME_BAND_SHARE("time_band_share"),
    /** 그 업종 매출의 성별 비중. {@code gender} */
    GENDER_SHARE("gender_share"),
    /** 그 업종 매출의 연령대 비중(4구간). {@code age_group} */
    AGE_SHARE("age_share");

    private final String code;

    PublicStatKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<PublicStatKind> fromCode(String code) {
        return Arrays.stream(values()).filter(k -> k.code.equals(code)).findFirst();
    }
}
