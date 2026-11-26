package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.EligibleStoreScope;
import java.time.Instant;
import java.util.List;

/** AssociationCouponEvent 응답(CONTRACTS_API 3.8). ID는 문자열, 시각은 UTC. 예산 값은 원. */
public record AssociationCouponEventResponse(
        String id,
        String title,
        BenefitType benefitType,
        Integer discountAmount,
        Integer discountPercent,
        Integer maxDiscountAmount,
        EligibleStoreScope eligibleStoreScope,
        List<String> storeIds,
        int totalQuantity,
        int issuedCount,
        int remainingQuantity,
        Instant issueStartAt,
        Instant issueEndAt,
        Instant validStartAt,
        Instant validEndAt,
        long reservedBudget,
        long usedBudget,
        long remainingBudget,
        Instant budgetReleasedAt,
        Instant confirmedAt,
        Instant stoppedAt,
        boolean issuable) {

    /**
     * @param reservedBudget 잡은 예산(만든 직후에는 D20 reserve가 돌려준 값, 그 밖에는 행의 값)
     * @param usedBudget 쓴 예산(3.8: 적용 · 사용된 쿠폰의 실제 할인액, 방문 전이면 한 장 최대 할인액)
     */
    public static AssociationCouponEventResponse from(AssociationCouponEvent event, long reservedBudget, long usedBudget,
            Instant now) {
        return new AssociationCouponEventResponse(
                String.valueOf(event.getId()),
                event.getTitle(),
                event.getBenefitType(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                event.getEligibleStoreScope(),
                event.getStoreIds().stream().sorted().map(String::valueOf).toList(),
                event.getTotalQuantity(),
                event.getIssuedCount(),
                event.remainingQuantity(),
                event.getIssueStartAt(),
                event.getIssueEndAt(),
                event.getValidStartAt(),
                event.getValidEndAt(),
                reservedBudget,
                usedBudget,
                reservedBudget - usedBudget,
                event.getBudgetReleasedAt(),
                event.getConfirmedAt(),
                event.getStoppedAt(),
                event.isIssuable(now));
    }
}
