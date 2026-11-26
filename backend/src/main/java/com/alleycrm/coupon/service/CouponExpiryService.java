package com.alleycrm.coupon.service;

import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.repository.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 만료 작업(E14, CONTRACTS_API 3.6, FR-CPN-05 #2 · #5, NFR-06). 실행 주기는 D7 스케줄이 정한다.
 * '예약에 적용됨'은 방문일이 기한을 넘어도 그대로다(FR-CPN-05 #3).
 */
@Service
public class CouponExpiryService {

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponExpiryService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    /** '다운로드됨'이고 만료 시각이 지금(서버 시계)이거나 지난 쿠폰을 '기간 만료'로 바꾸고, 바꾼 수를 돌려준다. */
    @Transactional
    public int expireDue() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return coupons.expireDue(now, CouponStatus.DOWNLOADED, CouponStatus.EXPIRED);
    }
}
