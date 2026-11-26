import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「2」의 상인회 월 정산(D17) 목. 권역은 로그인한 상인회 계정으로 정한다.
// 공동 기금 잔액 · 내역은 wallet.js(상인회 = 기금). 메모리에만 있어서 새로고침하면 처음으로 돌아간다.
// 목 데이터: 2026-10은 확정, 2026-09는 집계됨(확정 가능), 2026-11은 이번 달(확정 불가), 2026-08은 처리 안 된 신고 1건.

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

const MOCK_MONTHS = {
  '2026-11': {
    stores: [
      { storeId: '3', storeName: '골목카페', count: 2, totalFee: 3200, avgVisitAmount: 40000, couponCost: 5500 },
    ],
    reports: 0,
  },
  '2026-10': {
    stores: [
      { storeId: '1', storeName: '골목 한식당', count: 3, totalFee: 3600, avgVisitAmount: 30000, couponCost: 0 },
      { storeId: '3', storeName: '골목카페', count: 1, totalFee: 1000, avgVisitAmount: 25000, couponCost: 0 },
    ],
    reports: 0,
    confirmedAt: '2026-11-01T01:30:00Z',
  },
  '2026-09': {
    stores: [{ storeId: '2', storeName: '아침볕 커피', count: 2, totalFee: 1440, avgVisitAmount: 18000, couponCost: 3000 }],
    reports: 0,
  },
  '2026-08': {
    stores: [{ storeId: '4', storeName: '달밤 주점', count: 1, totalFee: 2400, avgVisitAmount: 60000, couponCost: 0 }],
    reports: 1,
  },
};
const confirmed = new Map(Object.entries(MOCK_MONTHS).filter(([, data]) => data.confirmedAt).map(([month, data]) => [month, data.confirmedAt]));

/** 그 달이 끝났는지(운영 시간대 기준, 목은 기기 시각) */
function monthEnded(month) {
  const thisMonth = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).format(new Date()).slice(0, 7);
  return month < thisMonth;
}

function settlementResponse(month) {
  const data = MOCK_MONTHS[month] ?? { stores: [], reports: 0 };
  const confirmedAt = confirmed.get(month) ?? null;
  const reports = data.reports;
  return {
    month,
    status: confirmedAt ? 'confirmed' : 'aggregated',
    confirmedAt,
    canConfirm: !confirmedAt && monthEnded(month) && reports === 0,
    unprocessedReportCount: reports,
    count: data.stores.reduce((sum, store) => sum + store.count, 0),
    totalFee: data.stores.reduce((sum, store) => sum + store.totalFee, 0),
    stores: data.stores,
  };
}

function rejectNonAssociation() {
  return rejectAnonymous() ?? (getMockAccount().role === 'association' ? null : errorResponse(403, 'forbidden_role', '상인회만 볼 수 있어요.'));
}

export const settlementHandlers = [
  // GET /api/v1/association/settlements/{month} (D17)
  http.get('/api/v1/association/settlements/:month', ({ params }) => {
    const blocked = rejectNonAssociation();
    if (blocked) return blocked;
    if (!MONTH.test(params.month)) return errorResponse(400, 'invalid', '달을 확인해 주세요.');
    return HttpResponse.json(settlementResponse(params.month));
  }),

  // POST /api/v1/association/settlements/{month}/confirm (D17) — 확인 기록. 잔액 · 수수료는 바꾸지 않는다
  http.post('/api/v1/association/settlements/:month/confirm', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const { month } = info.params;
    if (!MONTH.test(month)) return errorResponse(400, 'invalid', '달을 확인해 주세요.');
    const current = settlementResponse(month);
    if (current.status === 'confirmed') return errorResponse(409, 'conflict', '이미 확정한 달이에요.');
    if (!monthEnded(month)) return errorResponse(409, 'conflict', '그 달이 끝난 뒤에 확정할 수 있어요.');
    if (current.unprocessedReportCount > 0) return errorResponse(409, 'conflict', '처리되지 않은 신고가 있어 확정할 수 없어요.');
    confirmed.set(month, new Date().toISOString());
    return HttpResponse.json(settlementResponse(month));
  }),
];
