import { api } from '@/api/client.js';

// 비회원 예약 조회 — CONTRACTS_API.md 「1」 비회원 예약 조회(C13).

/**
 * 예약번호 + 전화번호로 확인하고 제한 세션을 시작한다(계정 세션이었다면 바뀐다). 응답은 그 예약(Reservation).
 * 실패: invalid(형식) · unauthorized(번호가 없거나 전화번호가 다름 — 서버가 둘을 구분하지 않는다).
 */
export function guestLookup({ reservationNo, phone }) {
  return api.post('/api/v1/guest/lookup', { reservationNo, phone });
}

/**
 * 제한 세션의 그 전화번호 비회원 예약 전체(방문 일시가 늦은 것부터). 로그인 안 함 401, 계정 세션 403.
 * @returns {Promise<{ items: object[] }>}
 */
export function getGuestReservations() {
  return api.get('/api/v1/guest/reservations');
}
