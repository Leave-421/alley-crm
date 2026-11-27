import { useCallback, useMemo, useState } from 'react';
import { MOCK_STORES } from './storeCatalog.js';

/**
 * 골목 3D 스크롤의 데이터 층(ARCHITECTURE 「3D 골목 보드」).
 * 가게 · 소속 골목 · 좌표 · 선택 상태 · 걸어 들어간 깊이만 들고, 어떻게 그릴지는 모른다 —
 * 그리는 층(AlleyCorridor · alleyCamera)이 이 값을 읽는다. 깊이의 범위 · 구역은 그리는 층이 정한다.
 * 같은 가게 데이터를 손님(G-01) · 사장님(O-07) · 상인회(A-03) 화면이 함께 쓴다(FR-MAP-02).
 *
 * @param {{ alley: string, stores?: object[], initialSelectedId?: string|null }} params
 */
export function useAlleyBoard({ alley, stores = MOCK_STORES, initialSelectedId = null }) {
  const storesInAlley = useMemo(
    () => stores.filter((store) => store.alley === alley).sort((a, b) => (a.mapX ?? 0) - (b.mapX ?? 0)),
    [stores, alley],
  );

  const [selectedStoreId, setSelectedStoreId] = useState(initialSelectedId);
  const [scrollDepth, setScrollDepth] = useState(0);
  const selectedStore = storesInAlley.find((store) => store.id === selectedStoreId) ?? null;

  const selectStore = useCallback((storeId) => setSelectedStoreId(storeId), []);
  const clearSelection = useCallback(() => setSelectedStoreId(null), []);

  return { storesInAlley, selectedStore, scrollDepth, setScrollDepth, selectStore, clearSelection };
}
