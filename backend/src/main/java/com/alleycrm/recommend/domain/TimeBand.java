package com.alleycrm.recommend.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * 시간대 6구간. 값은 docs/CONTRACTS.md 「3」 {@code timeBand}와 같다. 서울시 추정매출의 시간대 구간을 그대로 쓴다
 * (00 ~ 06 · 06 ~ 11 · 11 ~ 14 · 14 ~ 17 · 17 ~ 21 · 21 ~ 24). 추천 시간대 항(05-policy 2단계) · 오늘의 코스(FR-CRS-11)가 쓴다.
 */
public enum TimeBand {

    NIGHT("night", 0, 6),
    MORNING("morning", 6, 11),
    LUNCH("lunch", 11, 14),
    AFTERNOON("afternoon", 14, 17),
    EVENING("evening", 17, 21),
    LATE("late", 21, 24);

    private final String code;
    private final int startHour;
    private final int endHour;

    TimeBand(String code, int startHour, int endHour) {
        this.code = code;
        this.startHour = startHour;
        this.endHour = endHour;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** 그 시각(운영 시간대)이 속한 구간. */
    public static TimeBand of(LocalTime time) {
        int hour = time.getHour();
        return Arrays.stream(values()).filter(b -> hour >= b.startHour && hour < b.endHour).findFirst().orElseThrow();
    }

    public static Optional<TimeBand> fromCode(String code) {
        return Arrays.stream(values()).filter(b -> b.code.equals(code)).findFirst();
    }
}
