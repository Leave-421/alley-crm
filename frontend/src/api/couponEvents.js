import { api } from '@/api/client.js';

// 쿠폰 행사 설정 API — CONTRACTS_API.md 「3.2 쿠폰 행사 설정」(E11).
// 행사는 확정(저장)하면 고칠 수 없고 중단만 할 수 있다. 고치는 API는 없다.

/** 혜택 유형 enum(CONTRACTS.md 「3」 benefitType) */
export const BENEFIT_TYPES = Object.freeze({
  FIXED_DISCOUNT: 'fixed_discount',
  PERCENT_DISCOUNT: 'percent_discount',
  SERVICE: 'service',
});

/** 입력 범위(FR-CPN-01, 11-25). 서버가 다시 검사하고, 화면은 미리 알려 주는 데만 쓴다. */
export const COUPON_LIMITS = Object.freeze({
  validDaysMin: 1,
  validDaysMax: 30,
  validDaysDefault: 7,
  percentMin: 1,
  percentMax: 50,
  conditionsMaxLength: 200,
  serviceNameMaxLength: 50,
});

/** @returns {Promise<{ items: { value: string, label: string }[] }>} */
export function getBenefitTypes() {
  return api.get('/api/v1/coupon-events/benefit-types');
}

/** 진행 중인 행사. 없으면 current가 null. @returns {Promise<{ current: object | null }>} */
export function getMyCouponEvent() {
  return api.get('/api/v1/coupon-events/mine');
}

/** 행사 만들기 = 확정. 응답은 만든 CouponEvent. */
export function createCouponEvent(request) {
  return api.post('/api/v1/coupon-events', request);
}

/** 행사 중단. 다시 켜거나 고칠 수 없다. 응답은 중단된 CouponEvent. */
export function stopCouponEvent(eventId) {
  return api.post(`/api/v1/coupon-events/${encodeURIComponent(eventId)}/stop`);
}
