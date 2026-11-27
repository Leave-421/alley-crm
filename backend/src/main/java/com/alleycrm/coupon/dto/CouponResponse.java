package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import java.time.Instant;

/**
 * 받은 쿠폰(CONTRACTS_API 3.4 Coupon, 3.10에서 {@code issuerKind} · {@code associationCouponEventId}를 더함).
 * 혜택 칸은 받은 행사의 값이다(행사는 확정 뒤 바뀌지 않는다). 출처(추천 기준 가게 · 노출)는 기록만 하고 응답에 넣지 않는다.
 * 상인회 쿠폰은 가게 쿠폰 칸({@code eventId} · {@code storeId} · {@code serviceName} · {@code conditions})이 {@code null}이다.
 */
public record CouponResponse(
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

    /** 가게 쿠폰. */
    public static CouponResponse of(Coupon coupon, CouponPolicy event) {
        return new CouponResponse(
                String.valueOf(coupon.getId()),
                CouponIssuer.STORE,
                null,
                String.valueOf(event.getId()),
                String.valueOf(coupon.getTargetStoreId()),
                coupon.getStatus(),
                event.getBenefitType(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                event.getServiceName(),
                event.getConditions(),
                coupon.getDownloadedAt(),
                coupon.getExpiresAt());
    }

    /** 상인회 쿠폰(3.9 · 3.10). 쓸 수 있는 가게는 행사 카드(3.9)의 {@code stores}. */
    public static CouponResponse of(Coupon coupon, AssociationCouponEvent event) {
        return new CouponResponse(
                String.valueOf(coupon.getId()),
                CouponIssuer.ASSOCIATION,
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
