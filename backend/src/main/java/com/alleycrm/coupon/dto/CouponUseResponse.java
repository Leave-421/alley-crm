package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.ZoneOffset;

/** 쿠폰 사용 확인 응답(CONTRACTS_API 3.6). ID는 문자열, 시각은 UTC. */
public record CouponUseResponse(
        String couponId,
        String reservationId,
        CouponStatus status,
        Integer discountAmount,
        Instant usedAt) {

    public static CouponUseResponse from(Coupon coupon) {
        return new CouponUseResponse(
                String.valueOf(coupon.getId()),
                String.valueOf(coupon.getReservationId()),
                coupon.getStatus(),
                coupon.getDiscountApplied(),
                coupon.getUsedAt().toInstant(ZoneOffset.UTC));
    }
}
