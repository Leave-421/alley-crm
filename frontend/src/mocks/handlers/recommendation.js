import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';
import { findMockCustomerReservation } from './reservation.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';

// CONTRACTS_API.md 「3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

const RECOMMEND_COUNT = 3;
const REASONS = Object.freeze({
  visit_data: '이 가게 손님들이 같은 날 자주 찾는 참여 가게예요',
  local_popular: '이 동네에서 많이 찾는 가게예요',
});
const PUBLIC_DATA = Object.freeze({ source: '소상공인시장진흥공단 상가(상권)정보(목)', period: '2025년 2분기(목)' });
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 기준 가게를 뺀 목 가게를 기준 가게마다 다른 순서로 돌려 세운다(같은 기준 가게는 늘 같은 순서) */
function mockCandidates(baseStoreId) {
  const others = MOCK_STORES.filter((store) => store.id !== baseStoreId);
  const shift = MOCK_STORES.findIndex((store) => store.id === baseStoreId) * 4;
  return others.map((_, i) => others[(i + shift) % others.length]);
}

/** 한 묶음(최대 3곳) RecommendationCard. page는 pageCount로 나눈 나머지로 맞춘다(리롤 순환) */
function mockCards(baseStoreId, page) {
  const candidates = mockCandidates(baseStoreId);
  const pageCount = Math.ceil(candidates.length / RECOMMEND_COUNT);
  const current = page % pageCount;
  const items = candidates.slice(current * RECOMMEND_COUNT, current * RECOMMEND_COUNT + RECOMMEND_COUNT).map((store, i) => {
    // 첫 묶음 1 · 2위는 방문 데이터 기반, 나머지는 지역 인기(배지 · 안내 문구를 둘 다 볼 수 있게)
    const reasonType = current === 0 && i < 2 ? 'visit_data' : 'local_popular';
    return {
      rank: current * RECOMMEND_COUNT + i + 1,
      store: { id: store.id, name: store.name, category: store.category, alley: store.alley, mapX: store.mapX, mapY: store.mapY },
      badge: reasonType,
      reasonType,
      reasonText: REASONS[reasonType],
      couponBenefit: i === 0,
    };
  });
  const publicData = items.some((item) => item.badge === 'local_popular') ? PUBLIC_DATA : null;
  return { page: current, pageCount, items, publicData };
}

/** 정식 코스 제안의 검사(로그인 → 역할 → 요청자의 예약). 통과하면 { reservation }, 아니면 { blocked } */
function checkProposalRequest(baseReservationId) {
  const anonymous = rejectAnonymous();
  if (anonymous) return { blocked: anonymous };
  const account = getMockAccount();
  if (account.role !== 'customer') return { blocked: errorResponse(403, 'forbidden_role', '손님만 볼 수 있어요.') };
  const reservation = baseReservationId ? findMockCustomerReservation(baseReservationId) : null;
  // 없거나 남의 예약이면 404(존재를 숨김, IDOR)
  if (!reservation || reservation.accountId !== account.id) return { blocked: errorResponse(404, 'not_found', '예약을 찾을 수 없어요.') };
  return { reservation };
}

export const recommendationHandlers = [
  // GET /api/v1/recommendations?baseStoreId=&page= (E7) — 누구나, 기록하지 않음. 한 묶음 최대 3곳
  http.get('/api/v1/recommendations', ({ request }) => {
    const url = new URL(request.url);
    const baseStoreId = url.searchParams.get('baseStoreId');
    const page = Number(url.searchParams.get('page') ?? 0);
    if (!baseStoreId || !Number.isInteger(page) || page < 0) return errorResponse(400, 'invalid', '요청 값을 확인해 주세요.');
    if (!MOCK_STORES.some((store) => store.id === baseStoreId)) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    return HttpResponse.json(
      { baseStoreId, baseTime: new Date().toISOString(), baseTimeSource: 'now', ...mockCards(baseStoreId, page) },
      { headers: { 'Cache-Control': 'no-store' } },
    );
  }),

  // GET /api/v1/course-proposals?baseReservationId=&page= (E7) — 회원 손님의 자기 예약만. 기준 가게 = 그 예약의 가게
  http.get('/api/v1/course-proposals', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    if (!Number.isInteger(page) || page < 0) return errorResponse(400, 'invalid', '요청 값을 확인해 주세요.');
    const { blocked, reservation } = checkProposalRequest(url.searchParams.get('baseReservationId'));
    if (blocked) return blocked;
    // 소개 예약 신청 마감: 기준 예약일 다음 날 00:00(Asia/Seoul) 직전
    const dayStart = Date.parse(`${new Date(Date.parse(reservation.startAt) + KST_OFFSET_MS).toISOString().slice(0, 10)}T00:00:00Z`) - KST_OFFSET_MS;
    return HttpResponse.json(
      {
        baseReservationId: reservation.id,
        baseStoreId: reservation.storeId,
        baseTime: reservation.startAt,
        applyDeadline: new Date(dayStart + 86_400_000 - 1000).toISOString(),
        ...mockCards(reservation.storeId, page),
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
  }),

  // POST /api/v1/course-proposals (E7) — 그린 가게마다 courseProposalId. 같은 (기준 예약, 가게)는 같은 ID
  http.post('/api/v1/course-proposals', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const body = await info.request.json().catch(() => null);
    const storeIds = body?.storeIds;
    if (!Array.isArray(storeIds) || storeIds.length < 1 || storeIds.length > RECOMMEND_COUNT || new Set(storeIds).size !== storeIds.length) {
      return errorResponse(400, 'invalid', '가게 목록을 확인해 주세요.');
    }
    const { blocked, reservation } = checkProposalRequest(body.baseReservationId);
    if (blocked) return blocked;
    const candidates = new Set(mockCandidates(reservation.storeId).map((store) => store.id));
    if (storeIds.some((id) => !candidates.has(id))) return errorResponse(409, 'conflict', '추천 후보가 아닌 가게가 있어요.');
    const firstShownAt = new Date().toISOString();
    return HttpResponse.json({ items: storeIds.map((storeId) => ({ storeId, courseProposalId: `cp-${reservation.id}-${storeId}`, firstShownAt })) });
  }),
];
