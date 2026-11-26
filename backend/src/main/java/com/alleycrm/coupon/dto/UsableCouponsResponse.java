package com.alleycrm.coupon.dto;

import java.util.List;

/** 예약 화면의 사용 가능한 쿠폰(CONTRACTS_API 3.5). 원소는 3.4 Coupon. */
public record UsableCouponsResponse(List<CouponResponse> items) {
}
