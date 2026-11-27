package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.domain.CouponStatus;
import java.time.Instant;

/**
 * 받은 상인회 쿠폰(CONTRACTS_API 3.9): 3.4 Coupon 모양에 {@code issuerKind} · {@code associationCouponEventId}.
 * 가게 쿠폰 칸({@code eventId} · {@code storeId} · {@code serviceName} · {@code conditions})은 {@code null}이다.
 */
public record AssociationCouponResponse(
        String couponId,
        CouponIssuer issuerKind,
        String associationCouponEventId,
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

    public static AssociationCouponResponse of(Coupon coupon, AssociationCouponEvent event) {
        return new AssociationCouponResponse(
                String.valueOf(coupon.getId()),
                coupon.getIssuerKind(),
                String.valueOf(event.getId()),
                null,
                null,
                coupon.getStatus(),
                event.getBenefitType(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                null,
                null,
                coupon.getDownloadedAt(),
                coupon.getExpiresAt());
    }
}
