package com.alleycrm.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 서버의 현재 시각을 주는 단 하나의 시계(NFR-06, ARCHITECTURE 「서버 시각」).
 * 실제 시계에 '앞당긴 만큼(offset)'을 더해 돌려준다. 시연에서 시간을 앞당기면 자동 거절 · 자동 취소 ·
 * 쿠폰 만료 · 리마인드 · 파기가 모두 같은 시각을 기준으로 움직인다.
 *
 * <p>서버 코드는 {@code LocalDateTime.now()} · {@code Instant.now()}를 직접 부르지 않고
 * 이 빈(또는 {@link Clock} 빈)을 주입받아 {@code Instant.now(clock)}처럼 쓴다.
 * 테스트는 {@link #setTo(Instant)}로 시각을 고정할 수 있다.
 */
public class AppClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public AppClock(Clock base) {
        this.base = base;
    }

    public static AppClock systemUtc() {
        return new AppClock(Clock.systemUTC());
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("서버 시각은 UTC로만 쓴다");
    }

    /** 지금부터 duration만큼 앞당긴다(음수면 되돌림). */
    public void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    /** 현재 시각이 at이 되도록 맞춘다. */
    public void setTo(Instant at) {
        offset.set(Duration.between(base.instant(), at));
    }

    /** 실제 시각으로 되돌린다. */
    public void reset() {
        offset.set(Duration.ZERO);
    }

    public Duration offset() {
        return offset.get();
    }
}
