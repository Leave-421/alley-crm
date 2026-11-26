import { Link } from 'react-router';
import Button from '@/components/Button/Button.jsx';
import AlleyCorridor from './AlleyCorridor.jsx';
import StoreDetailPanel from './StoreDetailPanel.jsx';
import { SLOT_DEPTH, useAlleyBoard } from './useAlleyBoard.js';
import { ALLEY_LABELS } from './storeCatalog.js';
import styles from './AlleyScroll.module.css';

/**
 * 골목 3D 스크롤 공용 컴포넌트(A1, #4 · A4, #11). 손님 화면(G-01) · 사장님 손님 흐름(O-07) · 상인회
 * 방문 흐름(A-03)이 같은 가게 데이터 · 같은 보드를 함께 쓴다(ARCHITECTURE 「3D 골목 보드」, FR-MAP-02).
 *
 * 가게를 고르면 StoreDetailPanel(A4)이 확대 내용을 채운다. 추천 실제 내용(A5) · 쿠폰 배지(A6)는
 * 그 이슈에서 StoreDetailPanel 안에 더한다.
 */
export default function AlleyScroll({ alley, stores, initialSelectedId = null, backTo }) {
  const board = useAlleyBoard({ alley, stores, initialSelectedId });
  const alleyLabel = ALLEY_LABELS[alley] ?? alley;
  const storeCount = board.storesInAlley.length;
  const currentZone = storeCount > 0 ? Math.min(storeCount - 1, Math.round(board.scrollDepth / SLOT_DEPTH)) : 0;

  return (
    <div className={styles.page}>
      <div className={styles.topBar}>
        {backTo && (
          <Link to={backTo} className={styles.backLink}>
            ‹ 골목 고르기
          </Link>
        )}
        <h1 className={styles.title}>{alleyLabel} 골목</h1>
      </div>

      <div className={styles.boardArea}>
        <AlleyCorridor
          storesInAlley={board.storesInAlley}
          scrollDepth={board.scrollDepth}
          maxDepth={board.maxDepth}
          selectedStoreId={board.selectedStoreId}
          onScroll={board.setScrollDepth}
          onSelectStore={board.selectStore}
        />
      </div>

      <div className={styles.navBar}>
        <span className={styles.navLabel}>
          {alleyLabel} 골목 · 구역 {storeCount > 0 ? currentZone + 1 : 0} / {storeCount}
        </span>
        <div className={styles.navButtons}>
          {/* 깊이(scrollDepth)는 실제 창 스크롤 위치에서만 읽는다(AlleyCorridor) — 버튼은 state를
              직접 바꾸지 않고 창을 실제로 스크롤시켜, 그 다음 스크롤 이벤트가 깊이를 갱신하게 한다 */}
          <Button variant="secondary" onClick={() => window.scrollBy({ top: -SLOT_DEPTH, behavior: 'smooth' })} disabled={board.scrollDepth <= 0}>
            뒤로
          </Button>
          <Button onClick={() => window.scrollBy({ top: SLOT_DEPTH, behavior: 'smooth' })} disabled={board.scrollDepth >= board.maxDepth}>
            더 들어가기
          </Button>
        </div>
      </div>

      {board.selectedStore && (
        <div className={styles.selectedPanel}>
          <StoreDetailPanel store={board.selectedStore} alley={alley} alleyLabel={alleyLabel} onClose={board.clearSelection} />
        </div>
      )}
    </div>
  );
}
