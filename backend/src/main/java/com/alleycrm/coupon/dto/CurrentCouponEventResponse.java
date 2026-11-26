package com.alleycrm.coupon.dto;

/** {@code GET /api/v1/coupon-events/mine} 응답. 진행 중인 행사가 없으면 current는 null. */
public record CurrentCouponEventResponse(CouponEventResponse current) {
}
