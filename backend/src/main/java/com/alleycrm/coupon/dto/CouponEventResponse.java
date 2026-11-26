package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.CouponPolicy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** CouponEvent 응답(CONTRACTS_API 3.2). ID는 문자열, 시각은 UTC. */
public record CouponEventResponse(
        String id,
        String storeId,
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        String serviceName,
        Integer serviceValue,
        int validDays,
        String conditions,
        int totalQuantity,
        int issuedCount,
        int remainingQuantity,
        Instant confirmedAt,
        Instant stoppedAt,
        boolean benefitAvailable) {

    public static CouponEventResponse from(CouponPolicy policy) {
        return new CouponEventResponse(
                String.valueOf(policy.getId()),
                String.valueOf(policy.getStoreId()),
                policy.getBenefitType(),
                policy.getDiscountAmount(),
                policy.getDiscountPercent(),
                policy.getMaxDiscountAmount(),
                policy.getServiceName(),
                policy.getServiceValue(),
                policy.getValidDays(),
                policy.getConditions(),
                policy.getTotalQuantity(),
                policy.getIssuedCount(),
                policy.remainingQuantity(),
                toInstant(policy.getConfirmedAt()),
                toInstant(policy.getStoppedAt()),
                policy.isBenefitAvailable());
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
