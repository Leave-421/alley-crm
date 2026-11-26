package com.alleycrm.recommend.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/** 요일 구분. 값은 docs/CONTRACTS.md 「3」 {@code dayType}과 같다. 오늘의 코스 칸(FR-CRS-11)이 쓴다. */
public enum DayType {

    WEEKDAY("weekday"),
    WEEKEND("weekend");

    private final String code;

    DayType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** 토 · 일요일이면 주말. 날짜는 운영 시간대(Asia/Seoul) 날짜를 넘긴다. */
    public static DayType of(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? WEEKEND : WEEKDAY;
    }

    public static Optional<DayType> fromCode(String code) {
        return Arrays.stream(values()).filter(d -> d.code.equals(code)).findFirst();
    }
}
