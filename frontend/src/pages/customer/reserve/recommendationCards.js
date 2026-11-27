import { getCourseProposals, getRecommendations, recordCourseProposals } from '@/api/recommendations.js';

/**
 * 예약 하나를 기준으로 '다음에 갈 곳' 카드 묶음을 불러온다(완료 화면 G-02 · G-03, 내 예약 G-04가 같이 쓴다).
 * - useProposals(회원의 자기 예약): 정식 코스 제안(E7)을 조회하고 그린 가게를 표시 기록해 가게마다 courseProposalId를 붙인다
 *   — '다음 코스로 예약하기'가 그 ID를 달고 가야 소개 후보가 된다. 기준은 지도 추천과 같고 기준 시각만 그 예약 시각이다.
 * - 아니면(비회원 · 지난 예약 등) 그 가게 기준 지도 추천(기록 없음).
 * 카드마다 proposalId(없으면 null)가 붙는다.
 */
export async function loadRecommendationCards({ reservationId, storeId, useProposals }) {
  if (useProposals) {
    const data = await getCourseProposals(reservationId);
    const shown = data.items.map((card) => card.store.id);
    const recorded = shown.length > 0 ? await recordCourseProposals(reservationId, shown) : { items: [] };
    const proposalIds = new Map(recorded.items.map((item) => [item.storeId, item.courseProposalId]));
    return { ...data, items: data.items.map((card) => ({ ...card, proposalId: proposalIds.get(card.store.id) ?? null })) };
  }
  const data = await getRecommendations(storeId);
  return { ...data, items: data.items.map((card) => ({ ...card, proposalId: null })) };
}

/** '다음 코스로 예약하기' 주소와 넘길 값(G-03이 기준 예약의 가게 · 시각을 받는다) */
export function nextCourseLink(reservation, card) {
  const proposal = card.proposalId ? `&proposal=${encodeURIComponent(card.proposalId)}` : '';
  return {
    to: `/reservations/${encodeURIComponent(reservation.id)}/next?store=${encodeURIComponent(card.store.id)}${proposal}`,
    state: { rootStoreId: reservation.storeId, rootStartAt: reservation.startAt },
  };
}
