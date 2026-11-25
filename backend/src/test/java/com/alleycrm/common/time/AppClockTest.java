package com.alleycrm.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AppClockTest {

    private static final Instant REAL = Instant.parse("2026-11-27T09:00:00Z");
    private final AppClock clock = new AppClock(Clock.fixed(REAL, ZoneOffset.UTC));

    @Test
    void 처음에는_실제_시각() {
        assertThat(clock.instant()).isEqualTo(REAL);
        assertThat(clock.offset()).isEqualTo(Duration.ZERO);
    }

    @Test
    void 앞당기면_누적된다() {
        clock.advance(Duration.ofHours(24));
        clock.advance(Duration.ofMinutes(30));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-28T09:30:00Z"));
    }

    @Test
    void 특정_시각으로_맞추고_되돌린다() {
        clock.setTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));

        clock.reset();
        assertThat(clock.instant()).isEqualTo(REAL);
    }

    @Test
    void 시간대는_UTC() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
