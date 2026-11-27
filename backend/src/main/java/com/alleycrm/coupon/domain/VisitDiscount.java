package com.alleycrm.coupon.domain;

/**
 * 방문 할인 계산(05-policy 「혜택과 비용」, FR-CPN-07 #3 · #5 · #6). 방문 금액은 쿠폰 할인 전 결제 금액(원)이다.
 *
 * <ul>
 *   <li>정액 할인: 방문 금액까지만 깎고 남는 할인은 사라진다. 쿠폰 비용 = 할인액</li>
 *   <li>정률 할인: 방문 금액 × 할인율, 1원 미만 버림, 최대 할인액이 있으면 그 금액까지. 쿠폰 비용 = 할인액</li>
 *   <li>서비스 제공: 금액을 깎지 않는다(할인 0). 쿠폰 비용 = 사장님이 넣은 환산 금액</li>
 * </ul>
 */
public record VisitDiscount(int discountAmount, int couponCost) {

    /** 가게 쿠폰(3.2). */
    public static VisitDiscount of(CouponPolicy event, int visitAmount) {
        return of(event.getBenefitType(), event.getDiscountAmount(), event.getDiscountPercent(),
                event.getMaxDiscountAmount(), event.getServiceValue(), visitAmount);
    }

    /** 상인회 쿠폰(3.8 · 3.10). 정액 · 정률만 있고 쿠폰 비용 = 할인액(공동 기금 부담). */
    public static VisitDiscount of(AssociationCouponEvent event, int visitAmount) {
        return of(event.getBenefitType(), event.getDiscountAmount(), event.getDiscountPercent(),
                event.getMaxDiscountAmount(), null, visitAmount);
    }

    private static VisitDiscount of(BenefitType type, Integer discountAmount, Integer discountPercent,
            Integer maxDiscountAmount, Integer serviceValue, int visitAmount) {
        if (visitAmount < 0) {
            throw new IllegalArgumentException("방문 금액은 0 이상이다: " + visitAmount);
        }
        return switch (type) {
            case FIXED_DISCOUNT -> {
                int discount = Math.min(discountAmount, visitAmount);
                yield new VisitDiscount(discount, discount);
            }
            case PERCENT_DISCOUNT -> {
                long raw = (long) visitAmount * discountPercent / 100;
                int discount = (int) (maxDiscountAmount == null ? raw : Math.min(raw, maxDiscountAmount));
                yield new VisitDiscount(discount, discount);
            }
            case SERVICE -> new VisitDiscount(0, serviceValue == null ? 0 : serviceValue);
        };
    }
}
