import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';
import { addMockAccount } from './account.js';
import { addMockStore, findMockStore, mockRegionId, toMockStoreJoin, updateMockStore } from './store.js';

// CONTRACTS_API.md 「1」의 공개 권역 조회(GET /regions) · 사장님 쪽 가게 가입(C5: 신청 · 내 상태 · 재신청) 목.
// 상인회 쪽(목록 · 승인 · 거절)과 가게 목록은 store.js. 같은 가게 목록을 쓴다.

const CATEGORIES = ['restaurant', 'cafe', 'dessert', 'pub', 'workshop', 'bookstore', 'gift_shop', 'stationery'];
const ALLEYS = ['front_gate', 'center', 'back_street'];
const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** StoreJoinInfo 검사(계약 C5). 맞으면 저장할 값(휴무일은 요일 순), 틀리면 null. */
function parseStoreInfo(body) {
  if (!body || typeof body !== 'object') return null;
  const { name, category, alley, openTime, closeTime } = body;
  const closedDays = body.closedDays ?? [];
  if (typeof name !== 'string' || name.length < 1 || name.length > 50) return null;
  if (!CATEGORIES.includes(category) || !ALLEYS.includes(alley)) return null;
  if (!TIME.test(openTime ?? '') || !TIME.test(closeTime ?? '') || openTime >= closeTime) return null;
  if (!Array.isArray(closedDays) || closedDays.some((day) => !DAYS.includes(day)) || new Set(closedDays).size !== closedDays.length) return null;
  return { name, category, alley, openTime, closeTime, closedDays: DAYS.filter((day) => closedDays.includes(day)) };
}

/** 사장님(승인 전 · 거절 포함)의 내 가게. 손님 · 상인회는 403. */
function ownStore() {
  const blocked = rejectAnonymous();
  if (blocked) return { blocked };
  const account = getMockAccount();
  if (account.role !== 'owner') return { blocked: errorResponse(403, 'forbidden_role', '사장님만 볼 수 있어요.') };
  return { store: findMockStore(account.storeId) };
}

export const storeJoinHandlers = [
  // GET /api/v1/regions — 누구나. v1은 권역 1개
  http.get('/api/v1/regions', () => HttpResponse.json({ items: [{ id: mockRegionId, name: '대학가 골목' }] })),

  // POST /api/v1/store-joins — 누구나. 가게(requested)와 사장님 계정을 같이 만든다. 세션은 만들지 않는다
  http.post('/api/v1/store-joins', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const body = await info.request.json().catch(() => null);
    const storeInfo = parseStoreInfo(body);
    const validAccount =
      typeof body?.loginId === 'string' &&
      /^[a-z0-9]{4,30}$/.test(body.loginId) &&
      typeof body?.password === 'string' &&
      body.password.length >= 8 &&
      body.password.length <= 64;
    if (!storeInfo || !validAccount || typeof body.regionId !== 'string') return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    if (body.regionId !== mockRegionId) return errorResponse(404, 'not_found', '권역을 찾을 수 없어요.');
    const storeId = addMockStore({ ...storeInfo, joinStatus: 'requested', businessStatus: 'open', reservationEnabled: true });
    const account = addMockAccount({ loginId: body.loginId, password: body.password, role: 'owner', storeId });
    if (!account) {
      updateMockStore(storeId, { joinStatus: 'withdrawn' }); // 목: 만든 가게를 목록에서 숨긴다
      return errorResponse(409, 'conflict', '이미 쓰는 아이디예요.');
    }
    return HttpResponse.json(toMockStoreJoin(findMockStore(storeId)), { status: 201 });
  }),

  // GET /api/v1/store-joins/mine — 사장님(승인 전 · 거절 포함)
  http.get('/api/v1/store-joins/mine', () => {
    const { blocked, store } = ownStore();
    return blocked ?? HttpResponse.json(toMockStoreJoin(store));
  }),

  // POST /api/v1/store-joins/mine/reapply — 거절된 가입만 고친 정보로 다시 신청
  http.post('/api/v1/store-joins/mine/reapply', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const { blocked, store } = ownStore();
    if (blocked) return blocked;
    const storeInfo = parseStoreInfo(await info.request.json().catch(() => null));
    if (!storeInfo) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    if (store.joinStatus !== 'rejected') return errorResponse(409, 'conflict', '거절된 가입만 다시 신청할 수 있어요.');
    return HttpResponse.json(toMockStoreJoin(updateMockStore(store.id, { ...storeInfo, joinStatus: 'requested' })));
  }),
];
