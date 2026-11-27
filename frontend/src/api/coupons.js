import { api } from '@/api/client.js';

// 손님 쿠폰 — CONTRACTS_API.md 「3.5 예약에 쿠폰 적용」(E13).

/**
 * 예약 화면의 그 가게 사용 가능한 쿠폰(회원만, 만료 가까운 순). 비로그인 401, 회원이 아니면 403.
 * @returns {Promise<{ items: { couponId: string, benefitType: string, discountAmount: number | null, discountPercent: number | null, maxDiscountAmount: number | null, serviceName: string | null, conditions: string | null, expiresAt: string }[] }>}
 */
export function getUsableCoupons(storeId) {
  return api.get('/api/v1/coupons/usable', { query: { storeId } });
}

/** 쿠폰 혜택 한 줄(3.2 CouponEvent 혜택 칸): 정액 · 정률(최대 할인액) · 서비스 */
export function couponBenefitText(coupon) {
  if (coupon.benefitType === 'fixed_discount') return `${(coupon.discountAmount ?? 0).toLocaleString('ko-KR')}원 할인`;
  if (coupon.benefitType === 'percent_discount') {
    const cap = coupon.maxDiscountAmount ? ` (최대 ${coupon.maxDiscountAmount.toLocaleString('ko-KR')}원)` : '';
    return `${coupon.discountPercent}% 할인${cap}`;
  }
  return coupon.serviceName ? `${coupon.serviceName} 제공` : '서비스 제공';
}
