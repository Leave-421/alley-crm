import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「1」의 가게 가입(C5) · 가게 목록 · 위치(C6) 목. 계정(C2) 목은 account.js.
// 목 권역은 '1' 하나(목 상인회 계정의 regionId). 상태는 메모리에만 있어서 새로고침하면 처음으로 돌아간다.

const MOCK_REGION_ID = '1';
const ALLEYS = ['front_gate', 'center', 'back_street'];
const JOIN_STATUSES = ['requested', 'approved', 'rejected', 'withdraw_requested', 'withdrawn'];

const store = (id, name, category, alley, extra = {}) => ({
  id,
  regionId: MOCK_REGION_ID,
  name,
  category,
  alley,
  mapX: null,
  mapY: null,
  realLat: null,
  realLng: null,
  address: null,
  openTime: '11:00',
  closeTime: '21:00',
  closedDays: [],
  businessStatus: 'open',
  reservationEnabled: true,
  joinStatus: 'approved',
  ...extra,
});

let stores = [
  store('1', '골목 한식당', 'restaurant', 'front_gate', { mapX: 12.5, mapY: 40, address: '서울시 가상구 골목길 1', closedDays: ['mon'] }),
  store('2', '아침볕 커피', 'cafe', 'center', { mapX: 30, mapY: 30, address: '서울시 가상구 골목길 12', openTime: '08:00', closeTime: '20:00' }),
  store('3', '골목카페', 'cafe', 'center', { mapX: 34.25, mapY: 28.5, address: '서울시 가상구 골목길 14' }),
  store('4', '달밤 주점', 'pub', 'back_street', { mapX: 60, mapY: 12, address: null, openTime: '17:00', closeTime: '23:00', businessStatus: 'paused' }),
  store('5', '종이와 연필', 'stationery', 'front_gate', { reservationEnabled: false, closedDays: ['sat', 'sun'] }),
  store('6', '흙손 공방', 'workshop', 'back_street', { mapX: 72.1234, mapY: 18, address: '서울시 가상구 골목길 30', joinStatus: 'withdraw_requested' }),
  // 가입 신청 · 거절 가게(참여 가게 목록에는 나오지 않음). 신청 시각 순
  store('7', '새벽 베이커리', 'dessert', 'center', { joinStatus: 'requested', openTime: '07:00', closeTime: '19:00', closedDays: ['tue'] }),
  store('8', '책방 오후', 'bookstore', 'back_street', { joinStatus: 'requested', openTime: '12:00', closeTime: '21:00' }),
  store('9', '반짝 소품', 'gift_shop', 'front_gate', { joinStatus: 'rejected' }),
];

const isParticipating = (item) => item.joinStatus === 'approved' || item.joinStatus === 'withdraw_requested';

function toStore(item) {
  return { ...item, reservable: item.businessStatus === 'open' && item.reservationEnabled };
}

function toStoreJoin(item) {
  const { id, regionId, name, category, alley, openTime, closeTime, closedDays, joinStatus } = item;
  return { storeId: id, regionId, name, category, alley, openTime, closeTime, closedDays, joinStatus };
}

/** 상인회 API의 역할 검사: 로그인 안 함 401, 상인회가 아니면 403 forbidden_role. 통과하면 null. */
function rejectNonAssociation() {
  return (
    rejectAnonymous() ??
    (getMockAccount().role === 'association' ? null : errorResponse(403, 'forbidden_role', '상인회만 쓸 수 있어요.'))
  );
}

/** 위치 칸: null 또는 −999.9999 ~ 999.9999, 소수 넷째 자리까지 */
const isMapValue = (value) =>
  value === null ||
  (typeof value === 'number' && Math.abs(value) <= 999.9999 && Math.abs(Math.round(value * 10_000) - value * 10_000) < 1e-6);

function decideJoin(nextStatus) {
  return (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const found = stores.find((item) => item.id === info.params.storeId && item.regionId === getMockAccount().regionId);
    if (!found) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    if (found.joinStatus !== 'requested') return errorResponse(409, 'conflict', '이미 처리된 가입 신청이에요.');
    const updated = { ...found, joinStatus: nextStatus };
    stores = stores.map((item) => (item.id === found.id ? updated : item));
    return HttpResponse.json(toStoreJoin(updated));
  };
}

export const storeHandlers = [
  // GET /api/v1/stores?regionId= (C6) — 누구나. 참여 가게만, ID순
  http.get('/api/v1/stores', ({ request }) => {
    const regionId = new URL(request.url).searchParams.get('regionId');
    if (!regionId) return errorResponse(400, 'invalid', '권역을 확인해 주세요.');
    if (regionId !== MOCK_REGION_ID) return errorResponse(404, 'not_found', '권역을 찾을 수 없어요.');
    return HttpResponse.json({ items: stores.filter(isParticipating).map(toStore) });
  }),

  // PUT /api/v1/stores/{storeId}/location (C6) — 상인회, 자기 권역 가게만
  http.put('/api/v1/stores/:storeId/location', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonAssociation();
    if (blocked) return blocked;
    const found = stores.find((item) => item.id === info.params.storeId && item.regionId === getMockAccount().regionId);
    if (!found) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    const body = await info.request.json().catch(() => null);
    const address = body?.address ?? null;
    const valid =
      body &&
      (address === null || (typeof address === 'string' && address.length <= 200)) &&
      ALLEYS.includes(body.alley) &&
      isMapValue(body.mapX ?? null) &&
      isMapValue(body.mapY ?? null);
    if (!valid) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    const updated = { ...found, address, alley: body.alley, mapX: body.mapX ?? null, mapY: body.mapY ?? null };
    stores = stores.map((item) => (item.id === found.id ? updated : item));
    return HttpResponse.json(toStore(updated));
  }),

  // GET /api/v1/store-joins?status=&page=&size= (C5) — 상인회, 자기 권역, 오래된 신청부터
  http.get('/api/v1/store-joins', ({ request }) => {
    const blocked = rejectNonAssociation();
    if (blocked) return blocked;
    const params = new URL(request.url).searchParams;
    const status = params.get('status') ?? 'requested';
    const page = Number(params.get('page') ?? 0);
    const size = Number(params.get('size') ?? 20);
    if (!JOIN_STATUSES.includes(status) || !Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
      return errorResponse(400, 'invalid', '조회 조건을 확인해 주세요.');
    }
    const matched = stores.filter((item) => item.regionId === getMockAccount().regionId && item.joinStatus === status);
    return HttpResponse.json({
      items: matched.slice(page * size, (page + 1) * size).map(toStoreJoin),
      page,
      size,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
    });
  }),

  // POST /api/v1/store-joins/{storeId}/approve · /reject (C5)
  http.post('/api/v1/store-joins/:storeId/approve', decideJoin('approved')),
  http.post('/api/v1/store-joins/:storeId/reject', decideJoin('rejected')),
];
