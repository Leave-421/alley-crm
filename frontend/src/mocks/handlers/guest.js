import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount, setMockAccount } from '../session.js';
import { findMockGuestReservation, listMockGuestReservations } from './reservation.js';

// CONTRACTS_API.md 「1」 비회원 예약 조회(C13)의 목. 제한 세션은 목 로그인 상태에 role 'guest'(계정 없음) + 전화번호로 둔다.
// 그래서 로그아웃(C2 목)으로 끝나고, 알림함(C14 목)은 'guest' 몫 알림을 준다. 회원 전용 API는 role이 'customer'가 아니라 403이다.

export const guestHandlers = [
  // POST /api/v1/guest/lookup — 누구나. 맞으면 제한 세션 시작(계정 세션이었어도 바뀜), 응답은 그 예약
  http.post('/api/v1/guest/lookup', async (info) => {
    const blocked = rejectInvalidCsrf(info);
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    if (typeof body?.reservationNo !== 'string' || !body.reservationNo.trim() || !/^\d{10,11}$/.test(body?.phone ?? '')) {
      return errorResponse(400, 'invalid', '예약번호와 전화번호를 확인해 주세요.');
    }
    const reservation = findMockGuestReservation(body.reservationNo.trim(), body.phone);
    if (!reservation) return errorResponse(401, 'unauthorized', '예약번호 또는 전화번호가 맞지 않아요.');
    setMockAccount({ id: null, loginId: null, role: 'guest', storeId: null, regionId: null, guestPhone: body.phone });
    return HttpResponse.json(reservation);
  }),

  // GET /api/v1/guest/reservations — 제한 세션만(로그인 안 함 401, 계정 세션 403)
  http.get('/api/v1/guest/reservations', () => {
    const blocked = rejectAnonymous();
    if (blocked) return blocked;
    const session = getMockAccount();
    if (session.role !== 'guest') return errorResponse(403, 'forbidden_role', '비회원 예약 조회로 들어온 경우만 볼 수 있어요.');
    return HttpResponse.json({ items: listMockGuestReservations(session.guestPhone) });
  }),
];
