import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「2」의 가상 잔액 · 거래 내역(D9) · 가상 충전(D10) · 사장님 소개 수수료 내역 · 소개 실적(D16) 목.
// 지갑은 로그인 계정으로 정한다(회원 · 가게 · 기금). 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

const REFERRAL_FEE_RATE = 0.04;

// 목 가게 지갑: 소개 수수료로 마이너스(-1,200원)가 된 상태에서 시작한다(충전 필요 안내를 볼 수 있게)
const wallets = {
  customer: { balance: 30000, transactions: [] },
  store: {
    balance: -1200,
    transactions: [
      { id: 't3', type: 'referral_fee_charged', amount: -1680, reservationId: 'r-103', createdAt: '2026-11-24T11:20:00Z' },
      { id: 't2', type: 'referral_fee_charged', amount: -1520, reservationId: 'r-102', createdAt: '2026-11-18T10:05:00Z' },
      { id: 't1', type: 'charge', amount: 2000, reservationId: null, createdAt: '2026-11-01T01:00:00Z' },
    ],
  },
  fund: { balance: 3200, transactions: [] },
};
let nextTxId = 4;

// 목 가게의 수수료 행(D15가 방문 처리 때 만든다). 정산 달 = 방문 처리된 달(Asia/Seoul)
const FEES = [
  { reservationId: 'r-102', reservationNo: 'K7Q2M9XB', maskedName: '김*연', visitedAt: '2026-11-18T10:05:00Z', visitAmount: 41000, couponDiscount: 3000, feeBase: 38000, feeRate: 0.04, feeAmount: 1520, couponCost: 3000 },
  { reservationId: 'r-103', reservationNo: 'K7Q2M9XC', maskedName: '최*아', visitedAt: '2026-11-24T11:20:00Z', visitAmount: 42000, couponDiscount: 0, feeBase: 42000, feeRate: 0.04, feeAmount: 1680, couponCost: 2500 },
  { reservationId: 'r-091', reservationNo: 'K7Q2M9XD', maskedName: '이*우', visitedAt: '2026-10-20T09:00:00Z', visitAmount: 25000, couponDiscount: 0, feeBase: 25000, feeRate: 0.04, feeAmount: 1000, couponCost: 0 },
];
// 정산 달별 월 정산 상태(D17). 없으면 null(잠정)
const SETTLEMENT_STATUS = { '2026-10': 'confirmed' };
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

const monthOf = (iso) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).format(new Date(iso));

/** 로그인 계정의 지갑 종류(회원 customer · 사장님 store · 상인회 fund) */
function walletKind() {
  return { customer: 'customer', owner: 'store', association: 'fund' }[getMockAccount().role];
}

const walletResponse = (kind) => ({ ownerType: kind, balance: wallets[kind].balance, needsCharge: kind === 'store' && wallets[kind].balance < 0 });

function rejectNonOwner() {
  return rejectAnonymous() ?? (getMockAccount().role === 'owner' ? null : errorResponse(403, 'forbidden_role', '사장님만 볼 수 있어요.'));
}

export const walletHandlers = [
  // GET /api/v1/wallets/me (D9)
  http.get('/api/v1/wallets/me', () => rejectAnonymous() ?? HttpResponse.json(walletResponse(walletKind()))),

  // GET /api/v1/wallets/me/transactions (D9) — 최근 것부터, 공통 페이지
  http.get('/api/v1/wallets/me/transactions', ({ request }) => {
    const blocked = rejectAnonymous();
    if (blocked) return blocked;
    const params = new URL(request.url).searchParams;
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) return errorResponse(400, 'invalid', '조회 조건을 확인해 주세요.');
    const all = wallets[walletKind()].transactions;
    return HttpResponse.json({ items: all.slice(page * size, (page + 1) * size), page, size, totalElements: all.length, totalPages: Math.ceil(all.length / size) });
  }),

  // POST /api/v1/wallets/me/charge (D10) — 회원 · 가게만, 1,000 ~ 1,000,000원
  http.post('/api/v1/wallets/me/charge', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    const kind = walletKind();
    if (kind === 'fund') return errorResponse(403, 'forbidden_role', '공동 기금은 충전할 수 없어요.');
    const body = await info.request.json().catch(() => null);
    const amount = body?.amount;
    if (!Number.isInteger(amount) || amount < 1000 || amount > 1_000_000) return errorResponse(400, 'invalid', '충전 금액을 확인해 주세요.');
    wallets[kind].balance += amount;
    wallets[kind].transactions = [{ id: `t${nextTxId++}`, type: 'charge', amount, reservationId: null, createdAt: new Date().toISOString() }, ...wallets[kind].transactions];
    return HttpResponse.json(walletResponse(kind));
  }),

  // GET /api/v1/owner/referral-fees?month= (D16)
  http.get('/api/v1/owner/referral-fees', ({ request }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const month = new URL(request.url).searchParams.get('month');
    if (!month || !MONTH.test(month)) return errorResponse(400, 'invalid', '달을 확인해 주세요.');
    const items = FEES.filter((fee) => monthOf(fee.visitedAt) === month);
    return HttpResponse.json({
      month,
      settlementStatus: SETTLEMENT_STATUS[month] ?? null,
      referralFeeRate: REFERRAL_FEE_RATE,
      count: items.length,
      totalFee: items.reduce((sum, fee) => sum + fee.feeAmount, 0),
      totalCouponCost: items.reduce((sum, fee) => sum + fee.couponCost, 0),
      items,
    });
  }),

  // GET /api/v1/owner/referral-stats?month= (D16) — month 없으면 전체 기간
  http.get('/api/v1/owner/referral-stats', ({ request }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const month = new URL(request.url).searchParams.get('month');
    if (month !== null && !MONTH.test(month)) return errorResponse(400, 'invalid', '달을 확인해 주세요.');
    const received = FEES.filter((fee) => month === null || monthOf(fee.visitedAt) === month).length;
    return HttpResponse.json({ month, sentCount: month === null ? 5 : 3, receivedCount: received });
  }),
];
