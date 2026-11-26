package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.dto.CouponUseResponse;
import com.alleycrm.coupon.repository.CouponRepository;
import com.alleycrm.coupon.repository.ReservationStatusReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 상태 변경(E14, CONTRACTS_API 3.6): 사장님 사용 확인, 가게 운영 중단 · 탈퇴 때 적용 취소, 늦은 방문 처리 때 되돌리기.
 */
@Service
public class CouponStatusService {

    private static final String VISITED = "visited";

    private final CouponRepository coupons;
    private final ReservationStatusReader reservations;
    private final Clock clock;

    public CouponStatusService(CouponRepository coupons, ReservationStatusReader reservations, Clock clock) {
        this.coupons = coupons;
        this.reservations = reservations;
        this.clock = clock;
    }

    /**
     * 사장님 쿠폰 사용 확인(FR-CPN-08). 그 가게 예약이 아니면 404, 방문 처리 전 · 적용된 쿠폰 없음 · 이미 사용 확인이면 409.
     * 쿠폰 행을 잠가 두 번 눌러도 한 번만 바뀐다.
     */
    @Transactional
    public CouponUseResponse confirmUse(long storeId, long reservationId) {
        String status = reservations.findStatusOfStoreReservation(reservationId, storeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!VISITED.equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT, "방문 처리된 예약만 쿠폰 사용을 확인할 수 있습니다.");
        }
        Coupon coupon = coupons.findWithLockByReservationIdAndStatusIn(reservationId, List.of(CouponStatus.APPLIED))
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "사용 확인할 쿠폰이 없습니다."));
        coupon.markUsed(now());
        return CouponUseResponse.from(coupons.saveAndFlush(coupon));
    }

    /**
     * 대상 가게가 운영 중단(휴업 · 중단) · 탈퇴하면 그 가게의 '다운로드됨' · '예약에 적용됨' 쿠폰을 '적용 취소'로(FR-CPN-07 #4).
     * 영업 상태 · 탈퇴를 바꾸는 곳(C)이 같은 트랜잭션에서 부른다. 바꾼 수를 돌려준다.
     */
    @Transactional
    public int cancelUnusedForStore(long storeId) {
        return coupons.cancelForStore(storeId, List.of(CouponStatus.DOWNLOADED, CouponStatus.APPLIED),
                CouponStatus.APPLY_CANCELED);
    }

    /** 자동 취소된 예약을 늦게 방문 처리하면(11-25 (5)) 적용 취소된 쿠폰을 '예약에 적용됨'으로 되돌린다. 사용 확인은 따로다. */
    @Transactional
    public void restoreForLateVisit(long reservationId) {
        coupons.changeStatusOfReservation(reservationId, CouponStatus.APPLY_CANCELED, CouponStatus.APPLIED);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
