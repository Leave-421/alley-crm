package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.DownloadStatus;
import com.alleycrm.coupon.repository.CouponLimitRepository.PastDownload;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 회원별 발급 제한(FR-CPN-04): 같은 회원 · 같은 행사 단위, 30 × 24시간, 미사용 적용 쿠폰. */
class DownloadLimitTest {

    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");

    @Test
    void 받은_적이_없으면_받을_수_있다() {
        assertThat(DownloadLimit.check(List.of(), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.AVAILABLE, null));
    }

    @Test
    void 받은_지_10일이면_30일_대기이고_다음_가능일은_받은_시각_더하기_30일() {
        Instant downloadedAt = NOW.minus(10, ChronoUnit.DAYS);

        assertThat(DownloadLimit.check(List.of(past(downloadedAt, CouponStatus.DOWNLOADED)), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.WAITING, downloadedAt.plus(720, ChronoUnit.HOURS)));
    }

    @Test
    void 만료되거나_적용_취소돼도_30일_제한은_유지된다() {
        for (CouponStatus status : new CouponStatus[] {CouponStatus.EXPIRED, CouponStatus.APPLY_CANCELED, CouponStatus.USED}) {
            assertThat(DownloadLimit.check(List.of(past(NOW.minus(10, ChronoUnit.DAYS), status)), NOW).status())
                    .isEqualTo(DownloadStatus.WAITING);
        }
    }

    @Test
    void 정확히_30_곱하기_24시간이_지나면_받을_수_있다() {
        assertThat(DownloadLimit.check(List.of(past(NOW.minus(720, ChronoUnit.HOURS), CouponStatus.EXPIRED)), NOW).status())
                .isEqualTo(DownloadStatus.AVAILABLE);
        assertThat(DownloadLimit.check(List.of(past(NOW.minus(720, ChronoUnit.HOURS).plusNanos(1000), CouponStatus.EXPIRED)), NOW)
                .status()).isEqualTo(DownloadStatus.WAITING);
    }

    @Test
    void 가장_최근에_받은_쿠폰으로_센다() {
        Instant latest = NOW.minus(5, ChronoUnit.DAYS);
        List<PastDownload> past = List.of(
                past(NOW.minus(40, ChronoUnit.DAYS), CouponStatus.USED), past(latest, CouponStatus.EXPIRED));

        assertThat(DownloadLimit.check(past, NOW).nextDownloadableAt()).isEqualTo(latest.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void 미사용_적용_쿠폰이_있으면_31일이_지나도_받을_수_없다() {
        assertThat(DownloadLimit.check(List.of(past(NOW.minus(31, ChronoUnit.DAYS), CouponStatus.APPLIED)), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.APPLIED_UNUSED, null));
    }

    private static PastDownload past(Instant downloadedAt, CouponStatus status) {
        return new PastDownload(downloadedAt, status);
    }
}
