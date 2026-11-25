import { commonHandlers } from './common.js';
import { accountHandlers } from './account.js';
import { reservationHandlers } from './reservation.js';
import { recommendationHandlers } from './recommendation.js';
import { couponHandlers } from './coupon.js';

// 목 핸들러는 CONTRACTS_API.md 절(담당)별 파일로 나눈다. 이 파일은 합치기만 한다.
// recommendation.js = 3.1 다음 방문 추천, coupon.js = 3.2 쿠폰 행사 설정(둘 다 담당 E).
export const handlers = [
  ...commonHandlers,
  ...accountHandlers,
  ...reservationHandlers,
  ...recommendationHandlers,
  ...couponHandlers,
];
