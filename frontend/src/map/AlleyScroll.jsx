import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import AlleyCorridor from './AlleyCorridor.jsx';
import StoreSheet from './StoreSheet.jsx';
import { useAlleyBoard } from './useAlleyBoard.js';
import { ALLEY_LABELS, MOCK_STORES } from './storeCatalog.js';
import { buildFacadeImageMap, buildFillerNames } from './alleyIllustrations.js';
import { DESIGN_WIDTH } from './alleyCamera.js';
import chip from './chip.module.css';
import styles from './AlleyScroll.module.css';

const HEADER_HEIGHT = 56;
const MIN_FRAME_HEIGHT = 640; // 이보다 짧은 화면은 전체를 줄인다
const MAX_UI_SCALE = 1.1; // 넓은 화면에서 글자 · 버튼이 너무 커지지 않게

/**
 * 화면 전체를 목업 좌표계(목업 px)로 쓰고 배율 하나로 맞춘다. 폰(폭 390)은 배율 1이라 목업과 완전히 같고,
 * 더 작은 폰은 폭에 맞춰 줄인다. 넓은 화면은 배율을 1.1까지만 키우고 남는 폭은 머리말 · 골목이 채운다.
 */
function useFrameScale(ref) {
  const [box, setBox] = useState(() => ({ width: window.innerWidth, height: window.innerHeight }));
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => {
      setBox({ width: entry.contentRect.width, height: entry.contentRect.height });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);
  const scale = Math.min(box.width / DESIGN_WIDTH, box.height / MIN_FRAME_HEIGHT, MAX_UI_SCALE);
  return { scale, frameWidth: box.width / scale, frameHeight: box.height / scale };
}

/**
 * G-01 골목(3D 스크롤) · 가게 화면(A1 #4 · A4 #11). 확정 목업 GuestAlleyFront/Center/Back.dc.html을
 * 그대로 옮긴 화면이라 앱 공통 머리말 · 하단 탭 없이 목업의 머리말을 쓴다(07-screens G-01 이동에도 하단 탭 없음).
 * 데이터 층(useAlleyBoard) · 그리는 층(AlleyCorridor · alleyCamera · alleyIllustrations)은 사장님 O-07 ·
 * 상인회 A-03 화면도 같이 쓴다(FR-MAP-02).
 */
export default function AlleyScroll({ alley, stores = MOCK_STORES, initialSelectedId = null, backTo }) {
  const { storesInAlley, selectedStore, scrollDepth, setScrollDepth, selectStore, clearSelection } = useAlleyBoard({
    alley,
    stores,
    initialSelectedId,
  });
  const facadeImages = useMemo(() => buildFacadeImageMap(stores), [stores]);
  const fillerNames = useMemo(() => buildFillerNames(stores), [stores]);
  const viewportRef = useRef(null);
  const { scale, frameWidth, frameHeight } = useFrameScale(viewportRef);
  const [duration, setDuration] = useState(initialSelectedId ? '0s' : '0.2s');
  const [sheetMode, setSheetMode] = useState('peek');
  const alleyLabel = ALLEY_LABELS[alley] ?? alley;

  const changeDepth = useCallback(
    (depth, nextDuration) => {
      setScrollDepth(depth);
      setDuration(nextDuration);
    },
    [setScrollDepth],
  );

  function openStore(storeId) {
    selectStore(storeId);
    setDuration('0.56s');
    setSheetMode('peek');
  }

  function closeStore() {
    clearSelection();
    setDuration('0.48s');
    setSheetMode('peek');
  }

  return (
    <div ref={viewportRef} className={styles.viewport}>
      <div className={styles.design} style={{ width: frameWidth, height: frameHeight, transform: `scale(${scale})` }}>
        <AlleyCorridor
            alley={alley}
            alleyLabel={alleyLabel}
            storesInAlley={storesInAlley}
            facadeImages={facadeImages}
            fillerNames={fillerNames}
            depth={scrollDepth}
            duration={duration}
            selectedStoreId={selectedStore?.id ?? null}
            sceneWidth={frameWidth}
            sceneHeight={frameHeight - HEADER_HEIGHT}
            onDepthChange={changeDepth}
            onSelectStore={openStore}
          />

          {selectedStore && (
            <button type="button" className={`${chip.chip} ${styles.backToAlley}`} onClick={closeStore}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M15 18l-6-6 6-6" />
              </svg>
              <span>골목 둘러보기</span>
            </button>
          )}

          <div className={styles.headerWrap}>
            <header className={styles.header}>
              <div className={styles.headerLeft}>
                {backTo && (
                  <Link to={backTo} aria-label="뒤로" className={styles.iconLink}>
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M15 18l-6-6 6-6" />
                    </svg>
                  </Link>
                )}
                <h1 className={styles.headerTitle}>{alleyLabel} 골목</h1>
              </div>
              <div className={styles.headerRight}>
                <Link to="/notifications" aria-label="알림함" className={styles.iconLink}>
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M6 8a6 6 0 0112 0c0 7 3 9 3 9H3s3-2 3-9" />
                    <path d="M10.3 21a1.9 1.9 0 003.4 0" />
                  </svg>
                </Link>
                <Link to="/demo" aria-label="프로필 · 역할 전환(시연 도구)" className={styles.iconLink}>
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <circle cx="12" cy="12" r="9" />
                    <circle cx="12" cy="10" r="3" />
                    <path d="M6.5 18.5a6.5 6.5 0 0111 0" />
                  </svg>
                </Link>
              </div>
            </header>
          </div>

          <StoreSheet
            store={selectedStore}
            alley={alley}
            alleyLabel={alleyLabel}
            building={selectedStore ? facadeImages.get(selectedStore.id) : undefined}
            scale={scale}
            mode={sheetMode}
            onModeChange={setSheetMode}
            onClose={closeStore}
          />
      </div>
    </div>
  );
}
