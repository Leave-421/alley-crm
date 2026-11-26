import { useRef, useState } from 'react';
import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { CATEGORY_LABELS, formatClosedDays, formatHours } from './storeCatalog.js';
import { buildingMetrics } from './alleyIllustrations.js';
import BuildingFace from './BuildingFace.jsx';
import styles from './StoreSheet.module.css';

const SHEET_HEIGHT = 600;
const PEEK = 390;
const RANKS = [1, 2, 3];

/**
 * 가게 확대 시트(A4, #11). 확정 목업 GuestAlley*.dc.html의 아래 시트(손잡이 · 접힘/펼침 · 끌어내리면 닫힘)를
 * 그대로 옮겼다. 값은 목업 좌표계라, 끈 거리는 화면 배율(scale)로 나눠 목업 px로 바꾼다.
 *
 * 목업에 있지만 계약(Store) · 시드에 아직 필드가 없는 것 — 한 줄 태그 · 소개 · 대표 메뉴 · 편의 정보 · 가격대 —
 * 은 지어내지 않고 뺐다(B24에서 필드가 생기면 더한다). 예약 시간(날짜 · 시간)과 '다음 코스' 추천 내용은
 * 각 이슈(예약 · A5)에서 채우고, 여기서는 추천 자리만 둔다.
 */
const PHOTO_WIDTH = 358; // 시트 폭 390 - 좌우 16
const PHOTO_HEIGHT = 150; // 목업 사진 높이

/** 시트 사진 — 골목에서 본 것과 같은 건물(실제 이름 간판)을 폭에 맞추고, 큰 간판이 위쪽 1/5쯤 오게 자른다 */
function StorePhoto({ store, building }) {
  const { scaleY, height } = buildingMetrics(building, PHOTO_WIDTH);
  const mainSign = building.signs?.find((box) => !box.vertical);
  const wanted = mainSign ? PHOTO_HEIGHT * 0.2 - mainSign.y * scaleY : PHOTO_HEIGHT - height;
  const top = Math.min(0, Math.max(PHOTO_HEIGHT - height, wanted));
  return (
    <div className={styles.photo} role="img" aria-label={`${store.name} 가게 정면`}>
      <BuildingFace building={building} name={store.name} width={PHOTO_WIDTH} className={styles.photoInner} style={{ top }} />
    </div>
  );
}

export default function StoreSheet({ store, alley, alleyLabel, building, scale, mode, onModeChange, onClose }) {
  const [dragY, setDragY] = useState(null);
  const dragRef = useRef(null);
  const base = mode === 'full' ? 0 : PEEK;
  const y = !store ? SHEET_HEIGHT + 40 : (dragY ?? base);

  function handlePointerDown(event) {
    if (!store || event.target.closest('a, button:not([data-sheet-toggle])')) return;
    dragRef.current = { y0: event.clientY, base, moved: false };
  }

  function handlePointerMove(event) {
    const drag = dragRef.current;
    if (!drag) return;
    const dy = (event.clientY - drag.y0) / scale;
    if (!drag.moved && Math.abs(dy) > 5) {
      drag.moved = true;
      // 끌기 시작한 뒤에만 포인터를 잡는다 — 누르자마자 잡으면 손잡이 버튼의 클릭이 먹힌다
      event.currentTarget.setPointerCapture?.(event.pointerId);
    }
    if (drag.moved) setDragY(Math.max(0, Math.min(SHEET_HEIGHT, drag.base + dy)));
  }

  function handlePointerUp() {
    const drag = dragRef.current;
    dragRef.current = null;
    if (!drag?.moved) return;
    const finalY = dragY ?? drag.base;
    setDragY(null);
    if (finalY > PEEK + 90) {
      onClose();
      return;
    }
    onModeChange(finalY < PEEK * 0.55 ? 'full' : 'peek');
  }

  return (
    <div
      className={styles.sheet}
      role="region"
      aria-label="가게 정보"
      inert={!store}
      style={{ transform: `translate(-50%, ${y}px)`, transitionDuration: dragY != null ? '0s' : '0.42s' }}
    >
      <div
        className={styles.grip}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
      >
        <button
          type="button"
          data-sheet-toggle
          className={styles.toggle}
          onClick={() => onModeChange(mode === 'full' ? 'peek' : 'full')}
          aria-label={mode === 'full' ? '가게 정보 접기' : '가게 정보 펼치기'}
        >
          <span className={styles.handle} />
        </button>

        <div className={styles.name}>{store?.name}</div>
        {store && (
          <div className={styles.sub}>
            {CATEGORY_LABELS[store.category] ?? store.category} · {alleyLabel} 골목
          </div>
        )}

        <div className={styles.nextRow}>
          <span className={styles.nextLabel}>다음 코스</span>
          <div className={styles.nextChips} aria-hidden="true">
            {RANKS.map((rank) => (
              <span key={rank} className={styles.nextChip}>
                <span className={styles.nextRank}>{rank}</span>
                <span className={styles.skeleton} style={{ width: 52 }} />
              </span>
            ))}
          </div>
        </div>

        <div className={styles.ctaRow}>
          {store && (
            <Link
              to={`/alleys/${alley}/reserve?store=${encodeURIComponent(store.id)}`}
              className={`${buttonStyles.button} ${buttonStyles.primary} ${styles.cta}`}
            >
              {store.name} 예약하기
            </Link>
          )}
        </div>
      </div>

      <div className={styles.body}>
        {store && (
          <>
            <div className={styles.divider} />
            {building ? <StorePhoto store={store} building={building} /> : <div className={styles.photo} />}

            <div className={styles.sectionTitle}>가게 정보</div>
            <dl className={styles.info}>
              <div className={styles.infoRow}>
                <dt>영업시간</dt>
                <dd className={styles.num}>{formatHours(store.openTime, store.closeTime)}</dd>
              </div>
              <div className={styles.infoRow}>
                <dt>휴무일</dt>
                <dd>{formatClosedDays(store.closedDays)}</dd>
              </div>
              <div className={styles.infoRow}>
                <dt>주소</dt>
                <dd>{store.address ?? '-'} (가상)</dd>
              </div>
            </dl>

            <div className={styles.recTitle}>다음에 많이 찾은 곳 3곳</div>
            <div className={styles.cap}>추천 가게는 준비 중이에요</div>
            {RANKS.map((rank) => (
              <div key={rank} className={styles.recCard} aria-hidden="true">
                <span className={styles.recRank}>{rank}</span>
                <span className={styles.recText}>
                  <span className={styles.skeleton} style={{ width: 96, height: 14 }} />
                  <span className={styles.skeleton} style={{ width: 64, height: 10 }} />
                </span>
              </div>
            ))}
          </>
        )}
      </div>
    </div>
  );
}
