import { BENEFIT_TYPES } from '@/api/couponEvents.js';
import { formatWon } from './reservationFormat.js';

// 방문 처리 미리보기(O-01, FR-RSV-09 · FR-STL-02). 계산식은 계약 3.6 「방문 할인」과 같아서 서버 값과 같게 나온다.
// 확정 값은 방문 처리 응답(couponDiscountAmount)과 소개 판정(E15 · D15 뒤 응답)을 따른다.

/** 예약에 적용된 쿠폰(Reservation.coupon)을 한 줄로. coupon.discountAmount는 행사의 할인 금액이다. */
export function describeAppliedCoupon(coupon) {
  switch (coupon.benefitType) {
    case BENEFIT_TYPES.FIXED_DISCOUNT:
      return `${formatWon(coupon.discountAmount)} 할인 쿠폰`;
    case BENEFIT_TYPES.PERCENT_DISCOUNT:
      return coupon.maxDiscountAmount == null
        ? `${coupon.discountPercent}% 할인 쿠폰`
        : `${coupon.discountPercent}% 할인 쿠폰(최대 ${formatWon(coupon.maxDiscountAmount)})`;
    case BENEFIT_TYPES.SERVICE:
      return `${coupon.serviceName} 제공 쿠폰`;
    default:
      return '쿠폰 적용';
  }
}

/**
 * 방문 금액(쿠폰 할인 전)에 실제로 깎이는 쿠폰 할인액. 3.6 「방문 할인」:
 * 정액 min(할인 금액, 방문 금액) · 정률 floor(방문 금액 × % ÷ 100), 최대 할인액까지 · 서비스 0.
 */
export function couponDiscountFor(coupon, visitAmount) {
  if (!coupon) return 0;
  switch (coupon.benefitType) {
    case BENEFIT_TYPES.FIXED_DISCOUNT:
      return Math.min(coupon.discountAmount, visitAmount);
    case BENEFIT_TYPES.PERCENT_DISCOUNT: {
      const amount = Math.floor((visitAmount * coupon.discountPercent) / 100);
      return coupon.maxDiscountAmount == null ? amount : Math.min(amount, coupon.maxDiscountAmount);
    }
    default:
      return 0;
  }
}

/**
 * 방문 금액 → 쿠폰 할인 → 할인 후 금액 → 소개 수수료 미리보기.
 * 수수료는 추천으로 온 예약만, (방문 금액 − 실제 할인액) × referralFeeRate. 원 단위 미만은 버린다(미리보기, 확정은 서버).
 */
export function previewVisit({ visitAmount, coupon, fromRecommendation, referralFeeRate }) {
  const discount = couponDiscountFor(coupon, visitAmount);
  const afterDiscount = visitAmount - discount;
  const fee = fromRecommendation && referralFeeRate != null ? Math.floor(afterDiscount * referralFeeRate) : null;
  return { discount, afterDiscount, fee };
}
