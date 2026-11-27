import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「1」 알림(C14)의 목. 받는 쪽은 로그인 계정의 역할로 정한다(회원 · 사장님 · 상인회).
// 계약이 main에 머지된 엔드포인트만 더한다. 상태 변경 API는 rejectInvalidCsrf로 CSRF를 먼저 검사한다.
// 읽음 상태는 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

const minutesAgo = (minutes) => new Date(Date.now() - minutes * 60_000).toISOString();
const at = (minutesFromNow) => new Date(Date.now() + minutesFromNow * 60_000).toISOString();

// 역할별 목 알림(최근 것부터). payload에는 ID와 화면에 쓰는 값만 넣는다(손님 이름 · 전화번호 · 보낸 가게 없음, FR-CUS-05)
const NOTIFICATIONS = {
  customer: [
    { id: 'n-c1', type: 'reminder', payload: { reservationId: 'r-201' }, readAt: null, createdAt: minutesAgo(3) },
    { id: 'n-c2', type: 'coupon_status', payload: { couponId: 'cp-11', status: 'expired' }, readAt: null, createdAt: minutesAgo(80) },
    { id: 'n-c3', type: 'reservation_confirmed', payload: { reservationId: 'r-201' }, readAt: minutesAgo(70), createdAt: minutesAgo(95) },
    { id: 'n-c4', type: 'reservation_rejected', payload: { reservationId: 'r-198', nextCourseReservationIds: ['r-199'] }, readAt: null, createdAt: minutesAgo(60 * 20) },
    { id: 'n-c5', type: 'change_approved', payload: { reservationId: 'r-190' }, readAt: minutesAgo(60 * 30), createdAt: minutesAgo(60 * 50) },
    { id: 'n-c6', type: 'change_rejected', payload: { reservationId: 'r-188' }, readAt: minutesAgo(60 * 60), createdAt: minutesAgo(60 * 70) },
    { id: 'n-c7', type: 'reservation_canceled', payload: { reservationId: 'r-180' }, readAt: minutesAgo(60 * 90), createdAt: minutesAgo(60 * 96) },
    { id: 'n-c8', type: 'coupon_status', payload: { couponId: 'cp-09', status: 'used' }, readAt: minutesAgo(60 * 100), createdAt: minutesAgo(60 * 120) },
  ],
  owner: [
    { id: 'n-o1', type: 'delay_reported', payload: { reservationId: 'r-301', startAt: at(30), expectedArrivalAt: at(40) }, readAt: null, createdAt: minutesAgo(1) },
    { id: 'n-o2', type: 'change_requested', payload: { reservationId: 'r-302' }, readAt: null, createdAt: minutesAgo(50) },
    { id: 'n-o3', type: 'reservation_requested', payload: { reservationId: 'r-303' }, readAt: minutesAgo(30), createdAt: minutesAgo(120) },
    { id: 'n-o4', type: 'reservation_canceled', payload: { reservationId: 'r-295' }, readAt: minutesAgo(60 * 20), createdAt: minutesAgo(60 * 26) },
  ],
  association: [],
  // 비회원 제한 세션(C13): 그 전화번호 비회원 예약들의 알림. 쿠폰 알림은 없다(FR-NTF-03)
  guest: [
    { id: 'n-g1', type: 'reservation_confirmed', payload: { reservationId: 'g-1' }, readAt: null, createdAt: minutesAgo(40) },
    { id: 'n-g2', type: 'next_store_recommended', payload: { reservationId: 'g-2', baseStoreId: 'S12' }, readAt: minutesAgo(60 * 20), createdAt: minutesAgo(60 * 22) },
  ],
};

const listFor = (role) => NOTIFICATIONS[role] ?? [];

export const notificationHandlers = [
  // GET /api/v1/notifications (C14) — 로그인한 계정, 최근 것부터, 공통 페이지 + unreadCount
  http.get('/api/v1/notifications', ({ request }) => {
    const blocked = rejectAnonymous();
    if (blocked) return blocked;
    const params = new URL(request.url).searchParams;
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
      return errorResponse(400, 'invalid', '조회 조건을 확인해 주세요.');
    }
    const all = listFor(getMockAccount().role);
    return HttpResponse.json({
      items: all.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
      unreadCount: all.filter((item) => !item.readAt).length,
    });
  }),

  // POST /api/v1/notifications/{id}/read (C14) — 내 알림만, 이미 읽었으면 처음 읽은 시각 유지
  http.post('/api/v1/notifications/:notificationId/read', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    const found = listFor(getMockAccount().role).find((item) => item.id === info.params.notificationId);
    if (!found) return errorResponse(404, 'not_found', '알림을 찾을 수 없어요.');
    found.readAt ??= new Date().toISOString();
    return new HttpResponse(null, { status: 204 });
  }),
];
