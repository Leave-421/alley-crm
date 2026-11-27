package com.alleycrm.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 서비스 시간 규칙: 날짜 · 월 경계는 서울 기준, DB DATETIME은 UTC 벽시계로 저장한다.
 */
public final class ServiceTime {

    /** 날짜 · 월 경계를 정하는 서비스 시간대 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ServiceTime() {
    }

    /** DB DATETIME에 넣을 UTC 벽시계 값 */
    public static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    /** DB DATETIME(UTC 벽시계)을 Instant로. null은 null. */
    public static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
