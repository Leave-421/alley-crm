import { api } from '@/api/client.js';

// 손님 쿠폰 — CONTRACTS_API.md 「3.5 예약에 쿠폰 적용」(E13).

/**
 * 예약 화면의 그 가게 사용 가능한 쿠폰(회원만, 만료 가까운 순). 비로그인 401, 회원이 아니면 403.
 * @returns {Promise<{ items: { couponId: string, benefitType: string, discountAmount: number | null, discountPercent: number | null, maxDiscountAmount: number | null, serviceName: string | null, conditions: string | null, expiresAt: string }[] }>}
 */
export function getUsableCoupons(storeId) {
  return api.get('/api/v1/coupons/usable', { query: { storeId } });
}

/**
 * 추천 카드의 쿠폰 상세(3.4, 누구나). 진행 중인 행사 · 지금 받을 수 있는지(downloadStatus) · 다음 다운로드 가능일 · 내 쿠폰.
 * @returns {Promise<{ storeId: string, event: object | null, downloadStatus: 'unavailable' | 'login_required' | 'applied_unused' | 'waiting' | 'available', nextDownloadableAt: string | null, myCoupons: { couponId: string, eventId: string, status: string, expiresAt: string }[] }>}
 */
export function getCouponOffer(storeId) {
  return api.get(`/api/v1/coupon-offers/${encodeURIComponent(storeId)}`);
}

/**
 * 쿠폰 다운로드(3.4, 회원만). 그 카드를 그릴 때 받은 추천 노출 ID 하나만 보낸다 — 대상 가게 · 출처는 서버가 정한다(FR-CPN-10).
 * 실패: unauthorized(→ 로그인) · not_found(노출이 내 것이 아님) · conflict(발급 조건 어긋남).
 */
export function downloadCoupon(exposureId) {
  return api.post('/api/v1/coupons', { exposureId });
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
