import { commonHandlers } from './common.js';
import { accountHandlers } from './account.js';
import { storeHandlers } from './store.js';
import { storeJoinHandlers } from './storeJoin.js'; // 1의 권역 조회 · 사장님 쪽 가게 가입(C5)
import { reservationHandlers } from './reservation.js';
import { settlementHandlers } from './settlement.js'; // 2의 상인회 월 정산(D17)
import { walletHandlers } from './wallet.js'; // 2의 가상 잔액 · 충전 · 소개 수수료 내역(D9 · D10 · D16)
import { recommendationHandlers } from './recommendation.js';
import { couponHandlers } from './coupon.js';
import { associationCouponHandlers } from './associationCoupon.js'; // 3.8 상인회 쿠폰 행사(E18)
import { reservationSettingsHandlers } from './reservationSettings.js'; // 2의 D5 예약 운영 설정

// 목 핸들러는 CONTRACTS_API.md 절(담당)별 파일로 나눈다. 이 파일은 합치기만 한다.
// account.js = 1의 계정(C2), store.js = 1의 가게 가입 · 목록 · 위치(C5 · C6).
// recommendation.js = 3.1 다음 방문 추천, coupon.js = 3.2 쿠폰 행사 설정(둘 다 담당 E).
export const handlers = [
  ...commonHandlers,
  ...accountHandlers,
  ...storeHandlers,
  ...storeJoinHandlers,
  ...reservationHandlers,
  ...walletHandlers,
  ...settlementHandlers,
  ...recommendationHandlers,
  ...couponHandlers,
  ...associationCouponHandlers,
  ...reservationSettingsHandlers,
];
