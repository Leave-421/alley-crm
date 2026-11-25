import { BENEFIT_TYPES } from '@/api/couponEvents.js';

export function formatWon(amount) {
  return `${Number(amount).toLocaleString('ko-KR')}원`;
}

/** 행사 혜택을 한 줄로. 숫자는 계약의 CouponEvent 필드 그대로 쓴다. */
export function describeBenefit(event) {
  switch (event.benefitType) {
    case BENEFIT_TYPES.FIXED_DISCOUNT:
      return `${formatWon(event.discountAmount)} 할인`;
    case BENEFIT_TYPES.PERCENT_DISCOUNT:
      return event.maxDiscountAmount == null
        ? `${event.discountPercent}% 할인 (최대 할인액 없음)`
        : `${event.discountPercent}% 할인 (최대 ${formatWon(event.maxDiscountAmount)})`;
    case BENEFIT_TYPES.SERVICE:
      return `${event.serviceName} 제공 (쿠폰 비용 환산 ${formatWon(event.serviceValue)})`;
    default:
      return '알 수 없는 혜택';
  }
}
