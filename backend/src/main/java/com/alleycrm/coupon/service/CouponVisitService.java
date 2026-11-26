package com.alleycrm.coupon.service;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.VisitDiscount;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 할인(E14, CONTRACTS_API 3.6 「방문 할인」). 적용된 쿠폰의 행사 값과 방문 금액으로 실제 할인액 · 쿠폰 비용을 정한다.
 * D15(수수료)는 이벤트 처리 순서에 기대지 않고 {@link #discountFor}를 부른다.
 */
@Service
public class CouponVisitService {

    private static final List<CouponStatus> APPLIED_OR_USED = List.of(CouponStatus.APPLIED, CouponStatus.USED);

    private final CouponRepository coupons;
    private final CouponPolicyRepository events;

    public CouponVisitService(CouponRepository coupons, CouponPolicyRepository events) {
        this.coupons = coupons;
        this.events = events;
    }

    /** 예약에 적용된 쿠폰의 할인(D15용). 적용된 쿠폰이 없으면 빈 값. 기록하지 않는다. */
    @Transactional(readOnly = true)
    public Optional<VisitCouponDiscount> discountFor(long reservationId, int visitAmount) {
        return coupons.findByReservationIdAndStatusIn(reservationId, APPLIED_OR_USED)
                .filter(coupon -> coupon.getCouponPolicyId() != null) // 상인회 쿠폰은 E20이 더한다
                .map(coupon -> {
                    VisitDiscount discount = VisitDiscount.of(eventOf(coupon), visitAmount);
                    return new VisitCouponDiscount(coupon.getId(), discount.discountAmount(), discount.couponCost());
                });
    }

    /** 방문 처리 때 실제 할인액을 쿠폰에 남긴다. 상태는 '예약에 적용됨' 그대로(FR-CPN-08). 적용된 쿠폰이 없으면 아무것도 하지 않는다. */
    @Transactional
    public void recordDiscount(long reservationId, int visitAmount) {
        coupons.findWithLockByReservationIdAndStatusIn(reservationId, List.of(CouponStatus.APPLIED))
                .filter(coupon -> coupon.getCouponPolicyId() != null)
                .ifPresent(coupon -> {
                    coupon.recordDiscount(VisitDiscount.of(eventOf(coupon), visitAmount).discountAmount());
                    coupons.saveAndFlush(coupon);
                });
    }

    private CouponPolicy eventOf(Coupon coupon) {
        return events.findById(coupon.getCouponPolicyId())
                .orElseThrow(() -> new IllegalStateException("쿠폰의 행사가 없다: " + coupon.getCouponPolicyId()));
    }
}
