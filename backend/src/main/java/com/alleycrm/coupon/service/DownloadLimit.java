package com.alleycrm.coupon.service;

import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.DownloadStatus;
import com.alleycrm.coupon.repository.CouponLimitRepository.PastDownload;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 회원별 발급 제한(FR-CPN-04, 05-policy 「발급 제한」, 결정 기록 11-25). 같은 회원 · 같은 행사 단위로 센다.
 *
 * <ul>
 *   <li>같은 행사의 쿠폰이 예약에 적용된 채 아직 사용 완료가 아니면 받을 수 없다(30일이 지나도).</li>
 *   <li>같은 행사에서 받은 지 30 × 24시간이 안 됐으면 받을 수 없다. 만료 · 적용 취소된 쿠폰도 센다.</li>
 * </ul>
 * 가게가 휴업 · 운영 중단한 뒤의 제한 해제는 부르는 쪽이 그 전에 받은 쿠폰을 빼고 넘긴다.
 */
final class DownloadLimit {

    /** '쿠폰 재발급 제한' 30일(05-policy 설정값, 정해진 값). 30 × 24시간. */
    static final Duration REDOWNLOAD_WAIT = Duration.ofHours(30L * 24);

    record Result(DownloadStatus status, Instant nextDownloadableAt) {
    }

    private DownloadLimit() {
    }

    static Result check(List<PastDownload> past, Instant now) {
        if (past.stream().anyMatch(coupon -> coupon.status() == CouponStatus.APPLIED)) {
            return new Result(DownloadStatus.APPLIED_UNUSED, null);
        }
        return past.stream()
                .map(PastDownload::downloadedAt)
                .max(Comparator.naturalOrder())
                .map(latest -> latest.plus(REDOWNLOAD_WAIT))
                .filter(next -> now.isBefore(next))
                .map(next -> new Result(DownloadStatus.WAITING, next))
                .orElseGet(() -> new Result(DownloadStatus.AVAILABLE, null));
    }
}
