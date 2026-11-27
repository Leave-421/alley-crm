import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';
import { findMockStore } from './store.js';
import { reserveMockFundBudget } from './wallet.js';

// CONTRACTS_API.md 「3.8 상인회 쿠폰 행사」(담당 E, E18 #161)의 목. 권역은 로그인한 상인회 계정으로 정한다.
// 예산은 wallet.js의 공동 기금에서 잡는다(D20). 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

let events = [
  {
    id: '1',
    title: '11월 골목 첫 방문 할인',
    benefitType: 'fixed_discount',
    discountAmount: 2000,
    discountPercent: null,
    maxDiscountAmount: null,
    eligibleStoreScope: 'all',
    storeIds: [],
    totalQuantity: 20,
    issuedCount: 20,
    issueStartAt: '2026-11-01T00:00:00Z',
    issueEndAt: '2026-11-30T15:00:00Z',
    validStartAt: '2026-11-01T00:00:00Z',
    validEndAt: '2026-12-15T15:00:00Z',
    reservedBudget: 40000,
    usedBudget: 18000,
    budgetReleasedAt: null,
    confirmedAt: '2026-10-31T03:00:00Z',
    stoppedAt: null,
  },
];
let nextId = 2;

function toResponse(event) {
  const now = Date.now();
  const remainingQuantity = event.totalQuantity - event.issuedCount;
  const issuable =
    event.stoppedAt === null && Date.parse(event.issueStartAt) <= now && now < Date.parse(event.issueEndAt) && remainingQuantity >= 1;
  return { ...event, remainingQuantity, remainingBudget: event.reservedBudget - event.usedBudget, issuable };
}

function rejectNonAssociation() {
  return rejectAnonymous() ?? (getMockAccount().role === 'association' ? null : errorResponse(403, 'forbidden_role', '상인회만 쓸 수 있어요.'));
}

const isPositiveInt = (value) => Number.isInteger(value) && value >= 1;
const isTime = (value) => typeof value === 'string' && !Number.isNaN(Date.parse(value));

/** 3.8 「검사」 그대로. 맞으면 저장할 값, 틀리면 null */
function validate(body) {
  if (!body || typeof body !== 'object') return null;
  const { title, benefitType, eligibleStoreScope, totalQuantity, issueStartAt, issueEndAt, validStartAt, validEndAt } = body;
  if (typeof title !== 'string' || title.trim().length < 1 || title.length > 50) return null;
  if (!['fixed_discount', 'percent_discount'].includes(benefitType)) return null;
  const fixed = benefitType === 'fixed_discount';
  if (fixed && !isPositiveInt(body.discountAmount)) return null;
  if (!fixed && (!Number.isInteger(body.discountPercent) || body.discountPercent < 1 || body.discountPercent > 50 || !isPositiveInt(body.maxDiscountAmount))) return null;
  if (!isPositiveInt(totalQuantity)) return null;
  if (!['all', 'selected'].includes(eligibleStoreScope)) return null;
  let storeIds = [];
  if (eligibleStoreScope === 'selected') {
    storeIds = Array.isArray(body.storeIds) ? body.storeIds : [];
    const regionId = getMockAccount().regionId;
    const valid =
      storeIds.length >= 1 &&
      new Set(storeIds).size === storeIds.length &&
      storeIds.every((id) => {
        const store = findMockStore(id);
        return store && store.regionId === regionId && store.joinStatus === 'approved';
      });
    if (!valid) return null;
    storeIds = [...storeIds].sort((a, b) => Number(a) - Number(b));
  }
  if (![issueStartAt, issueEndAt, validStartAt, validEndAt].every(isTime)) return null;
  const [is, ie, vs, ve] = [issueStartAt, issueEndAt, validStartAt, validEndAt].map(Date.parse);
  if (!(is < ie && vs < ve && ie <= ve && ie > Date.now())) return null;
  return {
    title: title.trim(),
    benefitType,
    discountAmount: fixed ? body.discountAmount : null,
    discountPercent: fixed ? null : body.discountPercent,
    maxDiscountAmount: fixed ? null : body.maxDiscountAmount,
    eligibleStoreScope,
    storeIds,
    totalQuantity,
    issueStartAt,
    issueEndAt,
    validStartAt,
    validEndAt,
  };
}

export const associationCouponHandlers = [
  // GET /api/v1/association/coupon-events — 그 권역 행사 전부, 최근에 만든 순
  http.get('/api/v1/association/coupon-events', () => {
    const blocked = rejectNonAssociation();
    return blocked ?? HttpResponse.json({ items: [...events].reverse().map(toResponse) });
  }),

  // POST /api/v1/association/coupon-events — 만들기 = 확정, 기금에서 예산을 잡는다(모자라면 409 insufficient_balance)
  http.post('/api/v1/association/coupon-events', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const saved = validate(await info.request.json().catch(() => null));
    if (!saved) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    const id = String(nextId);
    const reservedBudget = saved.totalQuantity * (saved.discountAmount ?? saved.maxDiscountAmount);
    if (!reserveMockFundBudget(reservedBudget, id)) {
      return errorResponse(409, 'insufficient_balance', '공동 기금이 모자라 행사를 만들 수 없어요.');
    }
    nextId += 1;
    const event = { id, ...saved, issuedCount: 0, reservedBudget, usedBudget: 0, budgetReleasedAt: null, confirmedAt: new Date().toISOString(), stoppedAt: null };
    events = [...events, event];
    return HttpResponse.json(toResponse(event), { status: 201 });
  }),

  // POST /api/v1/association/coupon-events/{eventId}/stop — 새로 받기만 멈춘다. 예산은 지금 돌려주지 않는다
  http.post('/api/v1/association/coupon-events/:eventId/stop', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const found = events.find((event) => event.id === info.params.eventId);
    if (!found) return errorResponse(404, 'not_found', '행사를 찾을 수 없어요.');
    if (found.stoppedAt !== null) return errorResponse(409, 'conflict', '이미 중단한 행사예요.');
    const stopped = { ...found, stoppedAt: new Date().toISOString() };
    events = events.map((event) => (event.id === found.id ? stopped : event));
    return HttpResponse.json(toResponse(stopped));
  }),
];
