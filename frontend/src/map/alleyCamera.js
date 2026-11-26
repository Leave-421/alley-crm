import { SLOT_DEPTH } from './useAlleyBoard.js';

/**
 * 그리는 층의 배치 · 카메라 수학만 모은 파일(ARCHITECTURE 「3D 골목 보드」).
 * 데이터 층(useAlleyBoard)은 이 파일을 모르고, 이 파일도 가게가 어디서 왔는지 모른다 —
 * 그리는 방식을 바꿀 때(예: three.js 도입) 이 파일만 바꾸면 된다.
 *
 * ponytail: CSS perspective + translateZ/rotateY로 원근만 흉내낸 단순 카메라. 목업처럼 좌우 측벽
 * 보기(측면 스트립)·간판·전깃줄 레이어는 없음 — 필요해지면(A3 Spotlight 등) 이 파일에 보강.
 */

const FACADE_OFFSET_X = 140;
const FACADE_ROTATE_DEG = 18;

/** mapX 오름차순으로 정렬된 가게 목록 → 그리는 층의 좌/우 번갈이 슬롯 */
export function layoutSlots(storesInAlley) {
  return storesInAlley.map((store, index) => ({
    store,
    side: index % 2 === 0 ? 'L' : 'R',
    z: -(index * SLOT_DEPTH),
  }));
}

/** 스크롤 깊이(걸어 들어가기) 또는 선택된 가게(확대) → world 레이어 transform */
export function worldTransform({ scrollDepth, slots, selectedStoreId }) {
  const selected = slots.find((slot) => slot.store.id === selectedStoreId);
  if (selected) {
    const faceTurn = selected.side === 'L' ? 14 : -14;
    return `translateZ(${-selected.z + 220}px) rotateY(${faceTurn}deg) scale(1.5)`;
  }
  return `translateZ(${scrollDepth}px)`;
}

/** 한 가게 슬롯 → 그 가게 facade(정면) transform */
export function facadeTransform(slot) {
  const x = slot.side === 'L' ? -FACADE_OFFSET_X : FACADE_OFFSET_X;
  const rotate = slot.side === 'L' ? FACADE_ROTATE_DEG : -FACADE_ROTATE_DEG;
  return `translate3d(${x}px, 0, ${slot.z}px) rotateY(${rotate}deg)`;
}
