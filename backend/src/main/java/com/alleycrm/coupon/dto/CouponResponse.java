package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 받은 쿠폰(CONTRACTS_API 3.4 Coupon). 혜택 칸은 받은 행사의 값이다(행사는 확정 뒤 바뀌지 않는다).
 * 출처(추천 기준 가게 · 노출)는 기록만 하고 응답에 넣지 않는다.
 */
public record CouponResponse(
        String couponId,
        String eventId,
        String storeId,
        CouponStatus status,
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        String serviceName,
        String conditions,
        Instant downloadedAt,
        Instant expiresAt) {

    public static CouponResponse of(Coupon coupon, CouponPolicy event) {
        return new CouponResponse(
                String.valueOf(coupon.getId()),
                String.valueOf(event.getId()),
                String.valueOf(coupon.getTargetStoreId()),
                coupon.getStatus(),
                event.getBenefitType(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                event.getServiceName(),
                event.getConditions(),
                coupon.getDownloadedAt().toInstant(ZoneOffset.UTC),
                coupon.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
}
