import { api } from '@/api/client.js';

// 다음 방문 추천 — CONTRACTS_API.md 「3.1 다음 방문 추천」(E7).

/** 추천 근거 배지(CONTRACTS.md 「3」 badge) */
export const RECOMMENDATION_BADGE_LABELS = Object.freeze({
  visit_data: '방문 데이터 기반',
  local_popular: '지역 인기 가게',
});

/**
 * 기준 가게의 다음 방문 추천(최대 3곳 한 묶음). 로그인 불필요, 기록하지 않는다.
 * @returns {Promise<{ baseStoreId: string, page: number, pageCount: number, items: object[], publicData: { source: string, period: string } | null }>}
 */
export function getRecommendations(baseStoreId, page = 0) {
  return api.get('/api/v1/recommendations', { query: { baseStoreId, page } });
}
