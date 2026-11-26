import { api } from '@/api/client.js';

// 사장님 예약 API — CONTRACTS_API.md 「사장님 예약 (D3 · D4)」.
// 가게는 요청으로 보내지 않는다. 서버가 로그인한 사장님 계정의 가게로 정한다(IDOR 방지).

/** 예약 상태 enum(CONTRACTS.md 「3」 reservation.status)과 화면 글자. Badge tone과 값이 같다. */
export const RESERVATION_STATUS_LABELS = Object.freeze({
  requested: '신청',
  confirmed: '확정',
  visited: '방문',
  canceled: '취소',
});

/** 취소 사유 enum(CONTRACTS.md 「3」 reservation.cancelReason)과 화면 글자. 자동 거절도 '가게 거절'이다. */
export const CANCEL_REASON_LABELS = Object.freeze({
  customer_canceled: '손님 취소',
  store_rejected: '가게 거절',
  auto_canceled: '자동 취소',
});

/**
 * 날짜별 예약 목록(D4). date는 운영 시간대(Asia/Seoul) 'YYYY-MM-DD'. 시각 순, 하루치 전부.
 * @returns {Promise<{ date: string, items: object[] }>}
 */
export function getOwnerReservations(date) {
  return api.get('/api/v1/owner/reservations', { query: { date } });
}

/** 승인(D3): 신청 → 확정. 응답은 Reservation. 신청이 아니거나 예약 시각이 지났으면 409 conflict. */
export function confirmReservation(id) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(id)}/confirm`);
}

/** 방문 금액 범위(D11 「정책에 따라 정한 것」). 0원 방문도 기록할 수 있다. */
export const VISIT_AMOUNT_LIMITS = Object.freeze({ min: 0, max: 10_000_000 });

/**
 * 방문 처리(D11): 확정 → 방문. visitAmount는 손님이 낸 결제 금액(쿠폰 할인 전, 원).
 * 쿠폰 할인 · 소개 수수료는 서버가 계산하고, 쿠폰은 사용 완료로 바꾸지 않는다(사용 확인은 따로). 응답은 Reservation.
 */
export function visitReservation(id, visitAmount) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(id)}/visit`, { visitAmount });
}

/**
 * 쿠폰 사용 확인(E14 3.6): 방문 처리된 예약의 적용 쿠폰을 사용 완료로. 본문 없음.
 * 방문 전 · 적용 쿠폰 없음 · 이미 확인함이면 409 conflict.
 * @returns {Promise<{ couponId: string, reservationId: string, status: string, discountAmount: number, usedAt: string }>}
 */
export function confirmCouponUse(id) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(id)}/coupon-use`);
}

/** 거절(D3): 신청 → 취소(가게 거절). 예약금 100% 환불 · 쿠폰 적용 취소는 서버가 한다. 응답은 Reservation. */
export function rejectReservation(id) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(id)}/reject`);
}
