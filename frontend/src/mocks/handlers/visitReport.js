import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「1」의 방문 신고(C23) 상인회 쪽 목: 목록 · 사장님 확인 요청 · 결과 기록.
// 손님 이름 · 연락처 · 손님 ID는 응답에 없다. 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

let reports = [
  { id: '1', type: 'not_processed', status: 'received', result: null, storeId: '4', storeName: '달밤 주점', reservationId: 'r-081', reservationNo: 'K7Q2M9XP', startAt: '2026-08-21T11:00:00Z', month: '2026-08', visitAmount: null, claimedAmount: null, ownerReply: null, ownerRepliedAt: null, createdAt: '2026-08-23T02:00:00Z', handledAt: null },
  { id: '2', type: 'amount', status: 'owner_check_requested', result: null, storeId: '3', storeName: '골목카페', reservationId: 'r-104', reservationNo: 'K7Q2M9XR', startAt: '2026-11-20T10:00:00Z', month: '2026-11', visitAmount: 30000, claimedAmount: 25000, ownerReply: '영수증을 다시 보니 25,000원이 맞아요. 정정할게요.', ownerRepliedAt: '2026-11-22T05:00:00Z', createdAt: '2026-11-21T01:00:00Z', handledAt: null },
  { id: '3', type: 'amount', status: 'done', result: 'invalid', storeId: '1', storeName: '골목 한식당', reservationId: 'r-095', reservationNo: 'K7Q2M9XS', startAt: '2026-10-15T09:00:00Z', month: '2026-10', visitAmount: 42000, claimedAmount: 32000, ownerReply: '추가 주문이 있었어요.', ownerRepliedAt: '2026-10-17T03:00:00Z', createdAt: '2026-10-16T01:00:00Z', handledAt: '2026-10-18T02:00:00Z' },
  // 목 사장님 가게(골목카페, storeId '3')의 '맞음' 신고: 미처리(늦은 방문 처리) · 금액(정정)
  { id: '4', type: 'not_processed', status: 'done', result: 'valid', storeId: '3', storeName: '골목카페', reservationId: 'r-110', reservationNo: 'K7Q2M9XT', startAt: '2026-11-12T10:30:00Z', month: '2026-11', visitAmount: null, claimedAmount: null, ownerReply: '그날 방문하셨는데 처리를 놓쳤어요.', ownerRepliedAt: '2026-11-14T02:00:00Z', createdAt: '2026-11-13T01:00:00Z', handledAt: '2026-11-15T01:00:00Z' },
  { id: '5', type: 'amount', status: 'done', result: 'valid', storeId: '3', storeName: '골목카페', reservationId: 'r-099', reservationNo: 'K7Q2M9XU', startAt: '2026-11-05T09:00:00Z', month: '2026-11', visitAmount: 40000, claimedAmount: 34000, ownerReply: '할인 전 금액을 잘못 넣었어요.', ownerRepliedAt: '2026-11-07T02:00:00Z', createdAt: '2026-11-06T01:00:00Z', handledAt: '2026-11-08T01:00:00Z' },
];
const STATUSES = ['received', 'owner_check_requested', 'done'];

function rejectNonAssociation() {
  return rejectAnonymous() ?? (getMockAccount().role === 'association' ? null : errorResponse(403, 'forbidden_role', '상인회만 볼 수 있어요.'));
}

function update(id, fields) {
  reports = reports.map((report) => (report.id === id ? { ...report, ...fields } : report));
  return reports.find((report) => report.id === id);
}

// 신고된 예약의 목 상태(정정 · 늦은 방문 처리용). 예약 id → { status, cancelReason, visitAmount, fromRecommendation, corrections }
const reportedReservations = {
  'r-110': { status: 'canceled', cancelReason: 'auto_canceled', visitAmount: null, fromRecommendation: true, corrections: 0 },
  'r-099': { status: 'visited', cancelReason: null, visitAmount: 40000, fromRecommendation: true, corrections: 0 },
  'r-104': { status: 'visited', cancelReason: null, visitAmount: 30000, fromRecommendation: false, corrections: 0 },
};
const REFERRAL_FEE_RATE = 0.04;

/** 사장님 쪽 검사: 로그인 안 함 401, 사장님이 아니면 403 */
function rejectNonOwner() {
  return rejectAnonymous() ?? (getMockAccount().role === 'owner' ? null : errorResponse(403, 'forbidden_role', '사장님만 쓸 수 있어요.'));
}

/** 그 예약 · 그 종류에 '맞음' 신고가 있나(C23 hasValidReport) */
const validReportsOf = (reservationId, type) => reports.filter((report) => report.reservationId === reservationId && report.type === type && report.result === 'valid');

const isAmount = (value) => Number.isInteger(value) && value >= 0 && value <= 10_000_000;

export const visitReportHandlers = [
  // GET /api/v1/owner/visit-reports — 내 가게 신고 중 확인 요청을 받은 것(확인 요청 · 처리 완료), 최근 것부터
  http.get('/api/v1/owner/visit-reports', ({ request }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const params = new URL(request.url).searchParams;
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    const storeId = getMockAccount().storeId;
    const mine = reports.filter((report) => report.storeId === storeId && report.status !== 'received').sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return HttpResponse.json({ items: mine.slice(page * size, (page + 1) * size), page, size, totalElements: mine.length, totalPages: Math.ceil(mine.length / size) });
  }),

  // POST /api/v1/owner/visit-reports/{reportId}/reply — 확인 요청인 동안만, 다시 보내면 고쳐 쓴다
  http.post('/api/v1/owner/visit-reports/:reportId/reply', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    const reply = typeof body?.reply === 'string' ? body.reply.trim() : '';
    if (reply.length < 1 || reply.length > 200) return errorResponse(400, 'invalid', '답변을 1 ~ 200자로 넣어 주세요.');
    const found = reports.find((report) => report.id === info.params.reportId && report.storeId === getMockAccount().storeId);
    if (!found) return errorResponse(404, 'not_found', '신고를 찾을 수 없어요.');
    if (found.status !== 'owner_check_requested') return errorResponse(409, 'conflict', '확인 요청 중인 신고에만 답변할 수 있어요.');
    return HttpResponse.json(update(found.id, { ownerReply: reply, ownerRepliedAt: new Date().toISOString() }));
  }),

  // POST /api/v1/owner/reservations/{id}/amount-correction (D22) — '맞음' 금액 신고 한 건에 정정 한 번
  http.post('/api/v1/owner/reservations/:id/amount-correction', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    if (!isAmount(body?.correctedAmount)) return errorResponse(400, 'invalid', '정정 금액을 확인해 주세요.');
    const reservation = reportedReservations[info.params.id];
    if (!reservation) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    const valid = validReportsOf(info.params.id, 'amount');
    if (reservation.status !== 'visited' || reservation.corrections >= valid.length) {
      return errorResponse(409, 'conflict', "정정할 수 있는 '맞음' 금액 신고가 없어요.");
    }
    const originalAmount = reservation.visitAmount;
    const feeDifference = reservation.fromRecommendation ? Math.floor(body.correctedAmount * REFERRAL_FEE_RATE) - Math.floor(originalAmount * REFERRAL_FEE_RATE) : 0;
    reservation.visitAmount = body.correctedAmount;
    reservation.corrections += 1;
    return HttpResponse.json({ reservationId: info.params.id, originalAmount, correctedAmount: body.correctedAmount, feeDifference, settlementMonth: valid[0].month, createdAt: new Date().toISOString() });
  }),

  // POST /api/v1/owner/reservations/{id}/late-visit (D23) — '맞음' 미처리 신고가 있는 자동 취소 예약 → 방문
  http.post('/api/v1/owner/reservations/:id/late-visit', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    if (!isAmount(body?.visitAmount)) return errorResponse(400, 'invalid', '방문 금액을 확인해 주세요.');
    const reservation = reportedReservations[info.params.id];
    if (!reservation) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    if (reservation.status !== 'canceled' || reservation.cancelReason !== 'auto_canceled' || validReportsOf(info.params.id, 'not_processed').length === 0) {
      return errorResponse(409, 'conflict', '늦은 방문 처리를 할 수 있는 예약이 아니에요.');
    }
    Object.assign(reservation, { status: 'visited', cancelReason: null, visitAmount: body.visitAmount });
    const referralFee = reservation.fromRecommendation ? Math.floor(body.visitAmount * REFERRAL_FEE_RATE) : null;
    return HttpResponse.json({ id: info.params.id, status: 'visited', cancelReason: null, visitAmount: body.visitAmount, visitedAt: new Date().toISOString(), coupon: null, couponDiscountAmount: null, referralConfirmed: referralFee !== null, referralFee });
  }),

  // GET /api/v1/association/visit-reports?status=&page=&size= — 오래된 신고부터
  http.get('/api/v1/association/visit-reports', ({ request }) => {
    const blocked = rejectNonAssociation();
    if (blocked) return blocked;
    const params = new URL(request.url).searchParams;
    const status = params.get('status');
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    if ((status !== null && !STATUSES.includes(status)) || !Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
      return errorResponse(400, 'invalid', '조회 조건을 확인해 주세요.');
    }
    const matched = reports.filter((report) => status === null || report.status === status).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    return HttpResponse.json({ items: matched.slice(page * size, (page + 1) * size), page, size, totalElements: matched.length, totalPages: Math.ceil(matched.length / size) });
  }),

  // POST …/{reportId}/owner-check — 접수 → 사장님 확인 요청
  http.post('/api/v1/association/visit-reports/:reportId/owner-check', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const found = reports.find((report) => report.id === info.params.reportId);
    if (!found) return errorResponse(404, 'not_found', '신고를 찾을 수 없어요.');
    if (found.status !== 'received') return errorResponse(409, 'conflict', '접수 상태인 신고만 확인을 요청할 수 있어요.');
    return HttpResponse.json(update(found.id, { status: 'owner_check_requested' }));
  }),

  // POST …/{reportId}/result — 확인 요청 → 처리 완료(맞음 · 아님). 금액 · 예약은 바꾸지 않는다
  http.post('/api/v1/association/visit-reports/:reportId/result', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    if (!['valid', 'invalid'].includes(body?.result)) return errorResponse(400, 'invalid', '결과를 확인해 주세요.');
    const found = reports.find((report) => report.id === info.params.reportId);
    if (!found) return errorResponse(404, 'not_found', '신고를 찾을 수 없어요.');
    if (found.status !== 'owner_check_requested') return errorResponse(409, 'conflict', '사장님 확인 요청 상태인 신고만 결과를 기록할 수 있어요.');
    return HttpResponse.json(update(found.id, { status: 'done', result: body.result, handledAt: new Date().toISOString() }));
  }),
];
