import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { RECOMMENDATION_BADGE_LABELS, getRecommendations, recordRecommendationExposures } from '@/api/recommendations.js';
import { ALLEY_LABELS, CATEGORY_LABELS, formatClosedDays, formatHours } from './storeCatalog.js';
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
 * 은 지어내지 않고 뺐다(B24에서 필드가 생기면 더한다). 목업의 '도보 N분'은 거리를 응답에 넣지 않아(FR-MAP-08) 뺐다.
 *
 * '다음 코스' 칩 · '다음에 많이 찾은 곳 3곳' 카드(A5, #22)는 지도 추천(3.1 GET /recommendations)으로 채우고,
 * 그린 뒤 탐색 노출을 한 번 기록한다(POST /recommendation-exposures). 기록이 부족한 가게는 '지역 인기 3곳(공공데이터 기반 추정)'.
 * 누르면 같은 골목 가게는 바로 그 가게를 열고, 다른 골목 가게는 그 골목으로 가서 연다(목업).
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

/**
 * 기준 가게의 지도 추천 첫 묶음. 받은 묶음을 그린 뒤 탐색 노출을 기록한다 — 묶음마다 새 displayId,
 * 개발 모드의 이중 effect로 두 번 가도 같은 displayId라 서버가 한 번으로 친다(3.1 R-1).
 */
function useStoreRecommendations(storeId) {
  const [loaded, setLoaded] = useState(null); // { storeId, data, displayId } | { storeId, error }

  useEffect(() => {
    if (!storeId) return undefined;
    let active = true;
    getRecommendations(storeId)
      .then((data) => active && setLoaded({ storeId, data, displayId: crypto.randomUUID() }))
      .catch((error) => active && setLoaded({ storeId, error }));
    return () => {
      active = false;
    };
  }, [storeId]);

  const current = loaded?.storeId === storeId ? loaded : null;
  const [recorded, setRecorded] = useState(null); // { displayId, exposureIds: { [storeId]: exposureId } }

  useEffect(() => {
    const items = current?.data?.items;
    if (!items?.length) return undefined;
    let active = true;
    const { displayId } = current;
    recordRecommendationExposures({ displayId, baseStoreId: current.storeId, page: current.data.page, storeIds: items.map((card) => card.store.id) })
      .then((result) => active && setRecorded({ displayId, exposureIds: Object.fromEntries(result.items.map((item) => [item.storeId, item.exposureId])) }))
      .catch((error) => console.warn('추천 표시 기록 실패', error));
    return () => {
      active = false;
    };
  }, [current]);

  // 쿠폰 다운로드는 그 카드를 그렸을 때 받은 노출 ID로만 한다(3.4, FR-CPN-10)
  const exposureIds = current && recorded?.displayId === current.displayId ? recorded.exposureIds : {};
  return current ? { ...current, exposureIds } : null;
}

/** '예약 혜택' 카드 밑의 쿠폰 상세 진입(FR-CPN-02). 가게를 누를 때마다 팝업을 띄우지 않고, 손님이 이 줄을 눌렀을 때만 연다 */
function couponLink(card, alley, baseStoreId, exposureId) {
  const query = new URLSearchParams({ store: card.store.id, base: baseStoreId });
  if (exposureId) query.set('exposure', exposureId);
  return `/alleys/${alley}/coupon?${query}`;
}

/** 추천 가게로 가는 버튼 · 링크. 같은 골목이면 그 자리에서 열고, 다른 골목이면 그 골목으로 가서 연다 */
function RecommendationTarget({ card, alley, onOpenStore, className, children }) {
  if (card.store.alley === alley) {
    return (
      <button type="button" className={className} onClick={() => onOpenStore(card.store.id)}>
        {children}
      </button>
    );
  }
  return (
    <Link to={`/alleys/${card.store.alley}?focus=${encodeURIComponent(card.store.id)}`} className={className}>
      {children}
    </Link>
  );
}

export default function StoreSheet({ store, alley, alleyLabel, building, scale, mode, onModeChange, onClose, onOpenStore }) {
  const recommendation = useStoreRecommendations(store?.id ?? null);
  const recItems = recommendation?.data?.items ?? [];
  const thin = recItems.length > 0 && recItems.every((card) => card.badge === 'local_popular');
  const publicData = recommendation?.data?.publicData;
  const rankClass = (card) => (card.store.alley === alley ? styles.rankSame : styles.rankOther);
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
          {!recommendation && (
            <div className={styles.nextChips} aria-hidden="true">
              {RANKS.map((rank) => (
                <span key={rank} className={styles.nextChip}>
                  <span className={styles.nextRank}>{rank}</span>
                  <span className={styles.skeleton} style={{ width: 52 }} />
                </span>
              ))}
            </div>
          )}
          {recItems.length > 0 && (
            <div className={styles.nextChips}>
              {recItems.map((card) => (
                <RecommendationTarget key={card.store.id} card={card} alley={alley} onOpenStore={onOpenStore} className={styles.nextChip}>
                  <span className={`${styles.nextRank} ${rankClass(card)}`}>{card.rank}</span>
                  {card.store.name}
                </RecommendationTarget>
              ))}
            </div>
          )}
          {recommendation && recItems.length === 0 && <span className={styles.cap}>{recommendation.error ? '추천을 불러오지 못했어요' : '추천할 가게가 없어요'}</span>}
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

            <div className={styles.recTitle}>{thin ? '지역 인기 3곳 (공공데이터 기반 추정)' : '다음에 많이 찾은 곳 3곳'}</div>
            {thin && <div className={styles.cap}>이 가게는 아직 연계 방문 기록이 부족해서, 공공데이터로 추정한 지역 인기 가게를 보여드려요.</div>}
            {recItems.length > 0 && <div className={styles.cap}>누르면 그 가게 정면으로 바로 이동해요</div>}
            {!recommendation &&
              RANKS.map((rank) => (
                <div key={rank} className={styles.recCard} aria-hidden="true">
                  <span className={styles.recRank}>{rank}</span>
                  <span className={styles.recText}>
                    <span className={styles.skeleton} style={{ width: 96, height: 14 }} />
                    <span className={styles.skeleton} style={{ width: 64, height: 10 }} />
                  </span>
                </div>
              ))}
            {recommendation?.error && <div className={styles.cap}>추천을 불러오지 못했어요.</div>}
            {recommendation?.data && recItems.length === 0 && <div className={styles.cap}>지금은 추천할 가게가 없어요.</div>}
            {recItems.map((card) => (
              <div key={card.store.id} className={styles.recItem}>
              <RecommendationTarget card={card} alley={alley} onOpenStore={onOpenStore} className={card.couponBenefit ? `${styles.recCard} ${styles.recCardWithCoupon}` : styles.recCard}>
                <span className={`${styles.recRank} ${rankClass(card)}`}>{card.rank}</span>
                <span className={styles.recText}>
                  <span className={styles.recName}>{card.store.name}</span>
                  <span className={styles.recWhere}>
                    {CATEGORY_LABELS[card.store.category] ?? card.store.category} · {card.store.alley === alley ? '같은 골목' : `${ALLEY_LABELS[card.store.alley] ?? '다른'} 골목으로 이동`}
                  </span>
                  <span className={styles.recBadges}>
                    <span className={card.badge === 'local_popular' ? `${styles.recBadge} ${styles.recBadgeLocal}` : styles.recBadge}>{RECOMMENDATION_BADGE_LABELS[card.badge] ?? card.badge}</span>
                    {card.couponBenefit && <span className={`${styles.recBadge} ${styles.recBadgeBenefit}`}>예약 혜택</span>}
                  </span>
                  <span className={styles.recReason}>{card.reasonText}</span>
                </span>
              </RecommendationTarget>
              {card.couponBenefit && (
                <Link to={couponLink(card, alley, store.id, recommendation.exposureIds[card.store.id])} className={styles.recCoupon}>
                  <span>{card.store.name} 예약 혜택 · 쿠폰 보기</span>
                  <span aria-hidden="true">›</span>
                </Link>
              )}
              </div>
            ))}
            {recItems.some((card) => card.badge === 'local_popular') && (
              <p className={styles.recNotice}>
                '지역 인기 가게'는 이 가게와 이어진 방문 기록이 부족해 공공데이터로 추정한 가게예요.
                {publicData && ` 출처: ${publicData.source} · 기준: ${publicData.period}`}
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}
