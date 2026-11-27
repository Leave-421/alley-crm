import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「1」의 방문 신고(C23) 상인회 쪽 목: 목록 · 사장님 확인 요청 · 결과 기록.
// 손님 이름 · 연락처 · 손님 ID는 응답에 없다. 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

let reports = [
  { id: '1', type: 'not_processed', status: 'received', result: null, storeId: '4', storeName: '달밤 주점', reservationId: 'r-081', reservationNo: 'K7Q2M9XP', startAt: '2026-08-21T11:00:00Z', month: '2026-08', visitAmount: null, claimedAmount: null, ownerReply: null, ownerRepliedAt: null, createdAt: '2026-08-23T02:00:00Z', handledAt: null },
  { id: '2', type: 'amount', status: 'owner_check_requested', result: null, storeId: '3', storeName: '골목카페', reservationId: 'r-104', reservationNo: 'K7Q2M9XR', startAt: '2026-11-20T10:00:00Z', month: '2026-11', visitAmount: 30000, claimedAmount: 25000, ownerReply: '영수증을 다시 보니 25,000원이 맞아요. 정정할게요.', ownerRepliedAt: '2026-11-22T05:00:00Z', createdAt: '2026-11-21T01:00:00Z', handledAt: null },
  { id: '3', type: 'amount', status: 'done', result: 'invalid', storeId: '1', storeName: '골목 한식당', reservationId: 'r-095', reservationNo: 'K7Q2M9XS', startAt: '2026-10-15T09:00:00Z', month: '2026-10', visitAmount: 42000, claimedAmount: 32000, ownerReply: '추가 주문이 있었어요.', ownerRepliedAt: '2026-10-17T03:00:00Z', createdAt: '2026-10-16T01:00:00Z', handledAt: '2026-10-18T02:00:00Z' },
];
const STATUSES = ['received', 'owner_check_requested', 'done'];

function rejectNonAssociation() {
  return rejectAnonymous() ?? (getMockAccount().role === 'association' ? null : errorResponse(403, 'forbidden_role', '상인회만 볼 수 있어요.'));
}

function update(id, fields) {
  reports = reports.map((report) => (report.id === id ? { ...report, ...fields } : report));
  return reports.find((report) => report.id === id);
}

export const visitReportHandlers = [
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
