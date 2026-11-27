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

/**
 * 기준 예약의 정식 코스 제안(E7). 손님(회원 · 비회원 제한 세션)만, 남의 예약이면 404. 기록하지 않는다.
 * @returns {Promise<{ baseReservationId: string, baseStoreId: string, applyDeadline: string, page: number, pageCount: number, items: object[], publicData: object | null }>}
 */
export function getCourseProposals(baseReservationId, page = 0) {
  return api.get('/api/v1/course-proposals', { query: { baseReservationId, page } });
}

/**
 * 정식 코스 제안 표시 기록. 실제로 그린 가게(1 ~ 3곳)를 보내면 가게마다 courseProposalId를 돌려준다(조합당 1건).
 * '다음 코스로 예약하기'에서 그 ID를 예약 요청에 그대로 넘긴다(D12).
 * @returns {Promise<{ items: { storeId: string, courseProposalId: string, firstShownAt: string }[] }>}
 */
export function recordCourseProposals(baseReservationId, storeIds) {
  return api.post('/api/v1/course-proposals', { baseReservationId, storeIds });
}
