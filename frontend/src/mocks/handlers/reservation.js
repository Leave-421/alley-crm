import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「2. 예약 · 잔액 · 수수료 · 정산 · 서버 시계 (담당 D)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

// 시연용 시각 조작(D18). 목은 시연 도구가 켜진 서버처럼 동작한다. 새로고침하면 실제 시각으로 돌아간다.
let offsetMs = 0;

/** 한 요청 안에서는 같은 '지금'을 쓴다(맞춘 시각이 밀리초 차이로 1분 전으로 보이지 않게). */
function clockResponse(realNowMs = Date.now()) {
  return HttpResponse.json({
    now: new Date(realNowMs + offsetMs).toISOString(),
    offsetSeconds: Math.round(offsetMs / 1000),
  });
}

/** 사장님 API의 역할 검사: 로그인 안 함 401, 사장님이 아니면 403 forbidden_role. 통과하면 null. */
function rejectNonOwner() {
  return rejectAnonymous() ?? (getMockAccount().role === 'owner' ? null : errorResponse(403, 'forbidden_role', '사장님만 볼 수 있어요.'));
}

// 날짜별 예약 목록(D4)의 목 예약. 어느 날짜를 열어도 상태 · 취소 사유 · 추천 여부 · 회원 여부를 모두 볼 수 있게
// 같은 하루치를 그 날짜 시각으로 만든다. 시각은 운영 시간대(Asia/Seoul) 벽시계.
const MOCK_DAY = [
  { time: '17:30', partySize: 2, status: 'visited', cancelReason: null, customerType: 'member', maskedName: '이*우', fromRecommendation: false, requestNote: null, depositAmount: 0 },
  { time: '18:00', partySize: 3, status: 'confirmed', cancelReason: null, customerType: 'member', maskedName: '김*연', fromRecommendation: true, requestNote: null, depositAmount: 15000 },
  { time: '18:30', partySize: 2, status: 'canceled', cancelReason: 'customer_canceled', customerType: 'member', maskedName: '박*율', fromRecommendation: false, requestNote: null, depositAmount: 10000 },
  { time: '19:00', partySize: 2, status: 'confirmed', cancelReason: null, customerType: 'guest', maskedName: '김*코', fromRecommendation: false, requestNote: '창가 자리면 좋겠어요', depositAmount: 0 },
  { time: '19:30', partySize: 4, status: 'canceled', cancelReason: 'store_rejected', customerType: 'guest', maskedName: '정*늘', fromRecommendation: true, requestNote: null, depositAmount: 20000 },
  { time: '20:00', partySize: 1, status: 'canceled', cancelReason: 'auto_canceled', customerType: 'guest', maskedName: null, fromRecommendation: false, requestNote: null, depositAmount: 0 },
  { time: '20:30', partySize: 2, status: 'requested', cancelReason: null, customerType: 'member', maskedName: '최*아', fromRecommendation: true, requestNote: '아이 의자 1개', depositAmount: 10000 },
  { time: '21:30', partySize: 3, status: 'requested', cancelReason: null, customerType: 'guest', maskedName: '한*름', fromRecommendation: false, requestNote: null, depositAmount: 0 },
];

// 승인 · 거절(D3)로 바뀐 상태. 예약 id → { status, cancelReason }. 새로고침하면 처음으로 돌아간다.
const decided = new Map();

function mockOwnerReservations(date) {
  const day = date.replaceAll('-', '');
  return MOCK_DAY.map((item, index) => {
    const startAt = new Date(`${date}T${item.time}:00+09:00`);
    const id = `${day}-${index + 1}`;
    const { status, cancelReason } = decided.get(id) ?? item;
    return {
      id,
      reservationNo: `K7Q2M9X${'ABCDEFGH'[index]}`, // 8자, 헷갈리는 0 O 1 I 없음(계약)
      startAt: startAt.toISOString(),
      partySize: item.partySize,
      status,
      cancelReason,
      requestedAt: new Date(startAt.getTime() - 26 * 3_600_000).toISOString(),
      requestNote: item.requestNote,
      depositAmount: item.depositAmount,
      customerType: item.customerType,
      maskedName: item.maskedName,
      fromRecommendation: item.fromRecommendation,
    };
  });
}

/** 목 예약 id('YYYYMMDD-n')로 그날 목록에서 찾는다. 없으면 null. */
function findMockReservation(id) {
  const match = /^(\d{4})(\d{2})(\d{2})-\d+$/.exec(id);
  if (!match) return null;
  return mockOwnerReservations(`${match[1]}-${match[2]}-${match[3]}`).find((item) => item.id === id) ?? null;
}

/** 목록 항목 → 계약의 Reservation 응답 모양(D2). 손님 이름 · 회원 여부는 넣지 않는다. */
function toReservation(item) {
  const { customerType: _customerType, maskedName: _maskedName, ...rest } = item;
  return { ...rest, storeId: '3', coupon: null, courseRootId: null, visitAmount: null, visitedAt: null };
}

/** 승인 · 거절 공통: 신청 상태가 아니면 409, 승인은 예약 시각이 지났어도 409(자동 거절 대상). */
function decideReservation(next) {
  return (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const found = findMockReservation(info.params.id);
    if (!found) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    if (found.status !== 'requested') return errorResponse(409, 'conflict', '신청 상태인 예약만 처리할 수 있어요.');
    if (next.status === 'confirmed' && Date.parse(found.startAt) <= Date.now() + offsetMs) {
      return errorResponse(409, 'conflict', '예약 시각이 지나 승인할 수 없어요. 자동 거절돼요.');
    }
    decided.set(found.id, next);
    return HttpResponse.json(toReservation({ ...found, ...next }));
  };
}

export const reservationHandlers = [
  // POST /api/v1/owner/reservations/{id}/confirm · /reject (D3)
  http.post('/api/v1/owner/reservations/:id/confirm', decideReservation({ status: 'confirmed', cancelReason: null })),
  http.post('/api/v1/owner/reservations/:id/reject', decideReservation({ status: 'canceled', cancelReason: 'store_rejected' })),

  // GET /api/v1/owner/reservations?date= (D4) — 로그인한 사장님 가게의 그 날짜 예약, 시각 순
  http.get('/api/v1/owner/reservations', ({ request }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const date = new URL(request.url).searchParams.get('date');
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) {
      return errorResponse(400, 'invalid', '날짜를 확인해 주세요.');
    }
    return HttpResponse.json({ date, items: mockOwnerReservations(date) });
  }),

  http.get('/api/v1/demo/clock', () => rejectAnonymous() ?? clockResponse()),

  http.post('/api/v1/demo/clock', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    const hasAdvance = Number.isInteger(body?.advanceMinutes);
    const hasAt = typeof body?.at === 'string' && !Number.isNaN(Date.parse(body.at));
    if (hasAdvance === hasAt) return errorResponse(400, 'invalid', 'advanceMinutes와 at 중 하나만 보내 주세요.');
    const realNowMs = Date.now();
    offsetMs = hasAdvance ? offsetMs + body.advanceMinutes * 60_000 : Date.parse(body.at) - realNowMs;
    return clockResponse(realNowMs);
  }),

  http.delete('/api/v1/demo/clock', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    offsetMs = 0;
    return clockResponse();
  }),
];
