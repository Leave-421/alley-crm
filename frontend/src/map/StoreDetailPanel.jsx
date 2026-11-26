import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { CATEGORY_LABELS, formatClosedDays } from './storeCatalog.js';
import styles from './StoreDetailPanel.module.css';

const RECOMMENDATION_SLOTS = 3;

/**
 * 가게 확대(A4, #11). 업종 · 영업시간 · 휴무일 · 주소 + '다음에 많이 찾은 곳 3곳' 자리 + 예약하기 바.
 * 추천 내용(A5, #22)과 쿠폰 배지(A6, #26)는 그 이슈에서 이 자리에 채운다.
 * 한 줄 소개 · 대표 메뉴 · 편의 정보 · 사진 · 가격대(07-screens 「여유」)는 계약에 아직 그 필드가
 * 없어(B24 가게 정보 관리에서 들어올 예정) 만들지 않는다.
 */
export default function StoreDetailPanel({ store, alley, alleyLabel, onClose }) {
  return (
    <div className={styles.panel} role="region" aria-label={`${store.name} 정보`}>
      <button type="button" className={styles.closeButton} onClick={onClose}>
        ‹ 골목 둘러보기
      </button>

      <h2 className={styles.name}>{store.name}</h2>
      <p className={styles.meta}>
        {CATEGORY_LABELS[store.category] ?? store.category} · {alleyLabel} 골목
      </p>

      <dl className={styles.infoList}>
        <div className={styles.infoRow}>
          <dt>영업시간</dt>
          <dd>
            {store.openTime} ~ {store.closeTime}
          </dd>
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

      <section aria-label="다음에 많이 찾은 곳 3곳">
        <h3 className={styles.sectionTitle}>다음에 많이 찾은 곳 3곳</h3>
        <div className={styles.recSlots}>
          {Array.from({ length: RECOMMENDATION_SLOTS }, (_, index) => (
            <div key={index} className={styles.recSlot} aria-hidden="true" />
          ))}
        </div>
        <p className={styles.recNote}>추천은 다음 이슈(A5)에서 연결됩니다.</p>
      </section>

      <Link
        to={`/alleys/${alley}/reserve?store=${encodeURIComponent(store.id)}`}
        className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.reserveButton}`}
      >
        {store.name} 예약하기
      </Link>
    </div>
  );
}
