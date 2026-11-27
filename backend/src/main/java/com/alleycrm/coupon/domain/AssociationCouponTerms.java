package com.alleycrm.coupon.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 검사를 마친 상인회 쿠폰 행사 설정값(CONTRACTS_API 3.8). 고른 혜택 유형에 쓰지 않는 칸은 {@code null}이고,
 * 쓸 수 있는 가게가 {@code all}이면 {@code storeIds}는 비어 있다. 확정하면 바뀌지 않는다(FR-CPN-12).
 */
public record AssociationCouponTerms(
        String title,
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        int totalQuantity,
        EligibleStoreScope eligibleStoreScope,
        Set<Long> storeIds,
        Instant issueStartAt,
        Instant issueEndAt,
        Instant validStartAt,
        Instant validEndAt) {
}
