import { http, HttpResponse } from 'msw/http';
import { errorResponse } from '../responses.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';

// CONTRACTS_API.md 「3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

const RECOMMEND_COUNT = 3;
const REASONS = Object.freeze({
  visit_data: '이 가게 손님들이 같은 날 자주 찾는 참여 가게예요',
  local_popular: '이 동네에서 많이 찾는 가게예요',
});

/** 기준 가게를 뺀 목 가게를 기준 가게마다 다른 순서로 돌려 세운다(같은 기준 가게는 늘 같은 순서) */
function mockCandidates(baseStoreId) {
  const others = MOCK_STORES.filter((store) => store.id !== baseStoreId);
  const shift = MOCK_STORES.findIndex((store) => store.id === baseStoreId) * 4;
  return others.map((_, i) => others[(i + shift) % others.length]);
}

export const recommendationHandlers = [
  // GET /api/v1/recommendations?baseStoreId=&page= (E7) — 누구나, 기록하지 않음. 한 묶음 최대 3곳
  http.get('/api/v1/recommendations', ({ request }) => {
    const url = new URL(request.url);
    const baseStoreId = url.searchParams.get('baseStoreId');
    const page = Number(url.searchParams.get('page') ?? 0);
    if (!baseStoreId || !Number.isInteger(page) || page < 0) return errorResponse(400, 'invalid', '요청 값을 확인해 주세요.');
    if (!MOCK_STORES.some((store) => store.id === baseStoreId)) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
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
    const publicData = items.some((item) => item.badge === 'local_popular') ? { source: '소상공인시장진흥공단 상가(상권)정보(목)', period: '2025년 2분기(목)' } : null;
    return HttpResponse.json(
      { baseStoreId, baseTime: new Date().toISOString(), baseTimeSource: 'now', page: current, pageCount, items, publicData },
      { headers: { 'Cache-Control': 'no-store' } },
    );
  }),
];
