package com.alleycrm.coupon.dto;

/** {@code POST /api/v1/coupon-events} 요청(CONTRACTS_API 3.2). 검사는 서비스가 혜택 유형에 따라 한다. */
public record CouponEventCreateRequest(
        String benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        String serviceName,
        Integer serviceValue,
        Integer validDays,
        String conditions,
        Integer totalQuantity) {
}
