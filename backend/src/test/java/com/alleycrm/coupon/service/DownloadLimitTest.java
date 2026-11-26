package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.DownloadStatus;
import com.alleycrm.coupon.repository.CouponLimitRepository.PastDownload;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 회원별 발급 제한(FR-CPN-04): 같은 회원 · 같은 행사 단위, 30 × 24시간, 미사용 적용 쿠폰. */
class DownloadLimitTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 1, 9, 0);

    @Test
    void 받은_적이_없으면_받을_수_있다() {
        assertThat(DownloadLimit.check(List.of(), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.AVAILABLE, null));
    }

    @Test
    void 받은_지_10일이면_30일_대기이고_다음_가능일은_받은_시각_더하기_30일() {
        LocalDateTime downloadedAt = NOW.minusDays(10);

        assertThat(DownloadLimit.check(List.of(past(downloadedAt, CouponStatus.DOWNLOADED)), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.WAITING, downloadedAt.plusHours(720)));
    }

    @Test
    void 만료되거나_적용_취소돼도_30일_제한은_유지된다() {
        for (CouponStatus status : new CouponStatus[] {CouponStatus.EXPIRED, CouponStatus.APPLY_CANCELED, CouponStatus.USED}) {
            assertThat(DownloadLimit.check(List.of(past(NOW.minusDays(10), status)), NOW).status())
                    .isEqualTo(DownloadStatus.WAITING);
        }
    }

    @Test
    void 정확히_30_곱하기_24시간이_지나면_받을_수_있다() {
        assertThat(DownloadLimit.check(List.of(past(NOW.minusHours(720), CouponStatus.EXPIRED)), NOW).status())
                .isEqualTo(DownloadStatus.AVAILABLE);
        assertThat(DownloadLimit.check(List.of(past(NOW.minusHours(720).plusNanos(1000), CouponStatus.EXPIRED)), NOW)
                .status()).isEqualTo(DownloadStatus.WAITING);
    }

    @Test
    void 가장_최근에_받은_쿠폰으로_센다() {
        LocalDateTime latest = NOW.minusDays(5);
        List<PastDownload> past = List.of(
                past(NOW.minusDays(40), CouponStatus.USED), past(latest, CouponStatus.EXPIRED));

        assertThat(DownloadLimit.check(past, NOW).nextDownloadableAt()).isEqualTo(latest.plusDays(30));
    }

    @Test
    void 미사용_적용_쿠폰이_있으면_31일이_지나도_받을_수_없다() {
        assertThat(DownloadLimit.check(List.of(past(NOW.minusDays(31), CouponStatus.APPLIED)), NOW))
                .isEqualTo(new DownloadLimit.Result(DownloadStatus.APPLIED_UNUSED, null));
    }

    private static PastDownload past(LocalDateTime downloadedAt, CouponStatus status) {
        return new PastDownload(downloadedAt, status);
    }
}
