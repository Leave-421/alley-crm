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
