import { useCallback, useMemo, useState } from 'react';
import { MOCK_STORES } from './storeCatalog.js';

/** 가게 한 칸을 걸어 들어가는 스크롤 깊이(px). 그리는 층(alleyCamera.js)도 같은 값을 쓴다 */
export const SLOT_DEPTH = 460;

/**
 * 골목 3D 스크롤의 데이터 층(ARCHITECTURE 「3D 골목 보드」).
 * 가게 좌표 · 소속 골목 · 선택 상태만 들고, 어떻게 그릴지는 모른다 — 그리는 층(AlleyCorridor)이 이 값을 읽는다.
 * 같은 가게 데이터를 손님(G-01) · 사장님(O-07) · 상인회(A-03) 화면이 함께 쓴다(FR-MAP-02).
 *
 * @param {{ alley: string, stores?: object[], initialSelectedId?: string|null }} params
 */
export function useAlleyBoard({ alley, stores = MOCK_STORES, initialSelectedId = null }) {
  const storesInAlley = useMemo(
    () => stores.filter((store) => store.alley === alley).sort((a, b) => a.mapX - b.mapX),
    [stores, alley],
  );

  const [selectedStoreId, setSelectedStoreId] = useState(initialSelectedId);
  const [scrollDepth, setScrollDepthState] = useState(0);

  const maxDepth = Math.max(0, (storesInAlley.length - 1) * SLOT_DEPTH);
  const selectedStore = storesInAlley.find((store) => store.id === selectedStoreId) ?? null;

  // useCallback으로 고정: 그리는 층(AlleyCorridor)이 window scroll 이펙트의 의존성으로 쓴다
  const setScrollDepth = useCallback(
    (depth) => setScrollDepthState(Math.max(0, Math.min(maxDepth, depth))),
    [maxDepth],
  );
  const selectStore = useCallback((storeId) => setSelectedStoreId(storeId), []);
  const clearSelection = useCallback(() => setSelectedStoreId(null), []);

  return {
    storesInAlley,
    selectedStoreId,
    selectedStore,
    scrollDepth,
    maxDepth,
    setScrollDepth,
    selectStore,
    clearSelection,
  };
}
