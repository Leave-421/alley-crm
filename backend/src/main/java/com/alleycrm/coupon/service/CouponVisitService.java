package com.alleycrm.coupon.service;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.VisitDiscount;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.repository.AssociationCouponEventRepository;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 할인(E14 · E20, CONTRACTS_API 3.6 「방문 할인」 · 3.10). 적용된 쿠폰의 행사 값과 방문 금액으로 실제 할인액 · 쿠폰 비용을 정한다.
 * 가게 쿠폰은 가게 행사(3.2), 상인회 쿠폰은 상인회 행사(3.8) 값을 쓴다. D15(수수료)는 이벤트 처리 순서에 기대지 않고
 * {@link #discountFor}를 부른다.
 */
@Service
public class CouponVisitService {

    private static final List<CouponStatus> APPLIED_OR_USED = List.of(CouponStatus.APPLIED, CouponStatus.USED);

    private final CouponRepository coupons;
    private final CouponPolicyRepository events;
    private final AssociationCouponEventRepository associationEvents;

    public CouponVisitService(CouponRepository coupons, CouponPolicyRepository events,
            AssociationCouponEventRepository associationEvents) {
        this.coupons = coupons;
        this.events = events;
        this.associationEvents = associationEvents;
    }

    /** 예약에 적용된 쿠폰의 할인(D15용). 적용된 쿠폰이 없으면 빈 값. 기록하지 않는다. */
    @Transactional(readOnly = true)
    public Optional<VisitCouponDiscount> discountFor(long reservationId, int visitAmount) {
        return coupons.findByReservationIdAndStatusIn(reservationId, APPLIED_OR_USED)
                .map(coupon -> {
                    VisitDiscount discount = discountOf(coupon, visitAmount);
                    return new VisitCouponDiscount(coupon.getId(), coupon.getIssuerKind(), discount.discountAmount(),
                            discount.couponCost());
                });
    }

    /**
     * 방문 처리 때 실제 할인액을 쿠폰에 남긴다. 상태는 '예약에 적용됨' 그대로(FR-CPN-08 · 14 #4). 적용된 쿠폰이 없으면
     * 아무것도 하지 않는다. 상인회 쿠폰은 이 값이 공동 기금 예산에서 쓴 금액이다(3.10).
     */
    @Transactional
    public void recordDiscount(long reservationId, int visitAmount) {
        coupons.findWithLockByReservationIdAndStatusIn(reservationId, List.of(CouponStatus.APPLIED))
                .ifPresent(coupon -> {
                    coupon.recordDiscount(discountOf(coupon, visitAmount).discountAmount());
                    coupons.saveAndFlush(coupon);
                });
    }

    private VisitDiscount discountOf(Coupon coupon, int visitAmount) {
        if (coupon.getIssuerKind() == CouponIssuer.ASSOCIATION) {
            return VisitDiscount.of(associationEvents.findById(coupon.getAssociationEventId())
                    .orElseThrow(() -> new IllegalStateException("쿠폰의 상인회 행사가 없다: " + coupon.getAssociationEventId())),
                    visitAmount);
        }
        return VisitDiscount.of(events.findById(coupon.getCouponPolicyId())
                .orElseThrow(() -> new IllegalStateException("쿠폰의 행사가 없다: " + coupon.getCouponPolicyId())),
                visitAmount);
    }
}
