package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.AssociationCouponOfferStatus;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.EligibleStoreScope;
import java.time.Instant;
import java.util.List;

/** AssociationCouponOffer(CONTRACTS_API 3.9): 회원이 알림함에서 여는 상인회 쿠폰 행사 카드. 받은 수 · 예산은 넣지 않는다. */
public record AssociationCouponOfferResponse(
        String id,
        String title,
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        EligibleStoreScope eligibleStoreScope,
        List<OfferStore> stores,
        Instant issueStartAt,
        Instant issueEndAt,
        Instant validStartAt,
        Instant validEndAt,
        AssociationCouponOfferStatus status,
        String myCouponId) {

    /** 쓸 수 있는 가게(고른 가게만, ID 순). */
    public record OfferStore(String id, String name) {
    }

    public static AssociationCouponOfferResponse of(AssociationCouponEvent event, List<OfferStore> stores,
            AssociationCouponOfferStatus status, Long myCouponId) {
        return new AssociationCouponOfferResponse(
                String.valueOf(event.getId()),
                event.getTitle(),
                event.getBenefitType(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                event.getEligibleStoreScope(),
                stores,
                event.getIssueStartAt(),
                event.getIssueEndAt(),
                event.getValidStartAt(),
                event.getValidEndAt(),
                status,
                myCouponId == null ? null : String.valueOf(myCouponId));
    }
}
