import { useEffect, useRef } from 'react';
import { CATEGORY_LABELS } from './storeCatalog.js';
import { facadeTransform, layoutSlots, worldTransform } from './alleyCamera.js';
import styles from './AlleyCorridor.module.css';

/** 보드가 화면에 고정(sticky)돼 보이는 동안의 높이(px) */
export const STAGE_HEIGHT = 480;

/**
 * 그리는 층(ARCHITECTURE 「3D 골목 보드」). 데이터 층(useAlleyBoard)이 주는 가게 목록 · 스크롤 깊이 ·
 * 선택 상태만 읽어서 그린다 — 예약 · 추천 같은 화면 동작은 전혀 모른다.
 *
 * 앱 레이아웃(B2)은 화면 전체가 평범하게 스크롤되는 구조라(내부 overflow 컨테이너 없음, 하단 탭만
 * position: fixed) 목업처럼 자체 overflow:auto로 '걸어 들어가기'를 흉내낼 수 없다. 대신 흔한
 * scrollytelling 방식을 쓴다: 이 컴포넌트는 maxDepth만큼 키를 키운 긴 블록이고, 그 안의 stage만
 * position: sticky로 화면에 붙어 있는 동안, 컨테이너가 뷰포트 위로 올라간 만큼(-rect.top)을 깊이로 삼는다.
 */
export default function AlleyCorridor({ storesInAlley, scrollDepth, maxDepth, selectedStoreId, onScroll, onSelectStore }) {
  const containerRef = useRef(null);
  const rafRef = useRef(null);

  useEffect(() => {
    function measure() {
      rafRef.current = null;
      const el = containerRef.current;
      if (!el) return;
      const depth = Math.max(0, Math.min(maxDepth, -el.getBoundingClientRect().top));
      onScroll(depth);
    }
    function handleScroll() {
      if (rafRef.current == null) rafRef.current = requestAnimationFrame(measure);
    }
    measure();
    window.addEventListener('scroll', handleScroll, { passive: true });
    window.addEventListener('resize', handleScroll);
    return () => {
      window.removeEventListener('scroll', handleScroll);
      window.removeEventListener('resize', handleScroll);
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    };
  }, [maxDepth, onScroll]);

  const slots = layoutSlots(storesInAlley);
  const transform = worldTransform({ scrollDepth, slots, selectedStoreId });

  return (
    <div ref={containerRef} className={styles.scroller} style={{ height: STAGE_HEIGHT + maxDepth }}>
      <div className={styles.stage} style={{ height: STAGE_HEIGHT }} role="group" aria-label="골목 안 가게들">
        <div className={styles.world} style={{ transform }}>
          {slots.map((slot) => {
            const isSelected = slot.store.id === selectedStoreId;
            return (
              <button
                key={slot.store.id}
                type="button"
                className={isSelected ? `${styles.facade} ${styles.facadeSelected}` : styles.facade}
                style={{ transform: facadeTransform(slot) }}
                onClick={() => onSelectStore(slot.store.id)}
                aria-label={`${slot.store.name} 크게 보기`}
                aria-pressed={isSelected}
              >
                <span className={styles.facadeName}>{slot.store.name}</span>
                <span className={styles.facadeCat}>{CATEGORY_LABELS[slot.store.category] ?? slot.store.category}</span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
