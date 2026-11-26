import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { updateMyStoreStatus } from '@/api/ownerStore.js';
import { BUSINESS_STATUS_LABELS } from '@/api/stores.js';
import Button from '@/components/Button/Button.jsx';
import styles from './ReservationSettings.module.css';

// 바꾸면 어떻게 되는지(07-screens O-03, FR-STO-02 · FR-CPN-07, 11-24 (5))
const STATUS_EFFECTS = {
  open: '예약을 받고 추천 후보에 들어가요.',
  paused: '휴업 중에는 예약을 받을 수 없고 추천 후보에서 빠져요. 손님이 받아 둔 우리 가게 쿠폰은 적용 취소되고, 30일 제한도 풀려요.',
  closed: '운영 중단이면 예약을 받을 수 없고 추천 후보에서 빠져요. 손님이 받아 둔 우리 가게 쿠폰은 적용 취소되고, 30일 제한도 풀려요.',
};
const PAUSE_EFFECT = '예약 받기를 잠시 멈추면 새 예약과 추천 후보에서 빠지지만, 이미 받은 예약과 쿠폰은 그대로예요.';

/**
 * O-03 영업 상태 · 예약 받기(B10, FR-STO-02). 사장님이 직접 바꾸고 바로 반영된다(C22).
 * 이미 받은 예약은 그대로 둔다. 영업시간 · 휴무일은 O-12(B24).
 */
export default function StoreStatusForm({ store, onSaved }) {
  const id = useId();
  const [businessStatus, setBusinessStatus] = useState(store.businessStatus);
  const [reservationEnabled, setReservationEnabled] = useState(store.reservationEnabled);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const changed = businessStatus !== store.businessStatus || reservationEnabled !== store.reservationEnabled;

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const saved = await updateMyStoreStatus({ businessStatus, reservationEnabled });
      onSaved(saved);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '저장하지 못했어요.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={submit} aria-labelledby={`${id}-title`}>
      <div className={styles.cardHead}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          영업 상태 · 예약 받기
        </h2>
        <span className={store.reservable ? styles.stateOn : styles.stateOff}>{store.reservable ? '지금 예약 받는 중' : '지금 예약 안 받음'}</span>
      </div>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>영업 상태</legend>
        <div className={styles.choices}>
          {Object.entries(BUSINESS_STATUS_LABELS).map(([value, label]) => (
            <label key={value} className={styles.choice}>
              <input type="radio" name={`${id}-status`} value={value} checked={businessStatus === value} onChange={() => setBusinessStatus(value)} />
              {label}
            </label>
          ))}
        </div>
        <p className={businessStatus === 'open' ? styles.hint : styles.warning}>{STATUS_EFFECTS[businessStatus]}</p>
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>예약 받기</legend>
        <div className={styles.choices}>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-accept`} checked={reservationEnabled} onChange={() => setReservationEnabled(true)} />
            받는 중
          </label>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-accept`} checked={!reservationEnabled} onChange={() => setReservationEnabled(false)} />
            잠시 멈춤
          </label>
        </div>
        {!reservationEnabled && <p className={styles.warning}>{PAUSE_EFFECT}</p>}
      </fieldset>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <Button type="submit" disabled={saving || !changed} block>
        {saving ? '저장 중' : changed ? '영업 상태 저장' : '바뀐 내용 없음'}
      </Button>
    </form>
  );
}
