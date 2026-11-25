package com.alleycrm.coupon.domain;

/**
 * 검사를 마친 쿠폰 행사 설정값. 고른 혜택 유형에 쓰지 않는 칸은 {@code null}이다.
 * 행사를 확정하면 이 값들은 바뀌지 않는다(FR-CPN-01, 11-25).
 */
public record CouponEventTerms(
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        String serviceName,
        Integer serviceValue,
        int validDays,
        String conditions,
        int totalQuantity) {
}
