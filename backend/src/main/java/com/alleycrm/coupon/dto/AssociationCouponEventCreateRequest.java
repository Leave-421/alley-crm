package com.alleycrm.coupon.dto;

import java.util.List;

/**
 * {@code POST /api/v1/association/coupon-events} 요청(CONTRACTS_API 3.8). 시각은 ISO 8601 문자열(UTC)로 받고,
 * 검사는 서비스가 혜택 유형 · 쓸 수 있는 가게에 따라 한다.
 */
public record AssociationCouponEventCreateRequest(
        String title,
        String benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        String eligibleStoreScope,
        List<String> storeIds,
        Integer totalQuantity,
        String issueStartAt,
        String issueEndAt,
        String validStartAt,
        String validEndAt) {
}
