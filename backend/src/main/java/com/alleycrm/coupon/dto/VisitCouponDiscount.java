package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.CouponIssuer;

/**
 * 방문 처리된 예약의 쿠폰 할인(CONTRACTS_API 3.6 「방문 할인」). D15가 수수료 · 쿠폰 비용 기록에 쓴다.
 * 수수료 계산 금액 = 방문 금액 − {@code discountAmount}(서비스 제공 쿠폰은 0이라 원래 방문 금액).
 *
 * @param issuerKind 쿠폰 비용을 누가 내는지: 가게 쿠폰은 가게, 상인회 쿠폰은 공동 기금(11-25 (5))
 * @param discountAmount 실제로 깎은 금액(원)
 * @param couponCost 쿠폰 비용(정액 · 정률은 할인액, 서비스 제공은 환산 금액)
 */
public record VisitCouponDiscount(long couponId, CouponIssuer issuerKind, int discountAmount, int couponCost) {
}
