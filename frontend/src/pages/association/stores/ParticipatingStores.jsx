import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { BUSINESS_STATUS_LABELS, LOCATION_LIMITS, STORE_ALLEY_LABELS, updateStoreLocation } from '@/api/stores.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { alleyLabel, categoryLabel, formatHours, formatMapPoint } from './storeFormat.js';
import styles from './StoreManagement.module.css';

/**
 * 참여 가게 목록(FR-STO-02, 계약 C6 GET /stores). 승인 · 탈퇴 신청 가게, ID순.
 * 지도 위치 · 주소 · 골목은 상인회가 고치고, 영업 상태 · 예약 받기 · 영업시간은 사장님이 바꾼 값을 보기만 한다(11-24 (5)).
 */
export default function ParticipatingStores({ stores, onUpdated }) {
  const id = useId();
  return (
    <section className={styles.section} aria-labelledby={`${id}-title`}>
      <div className={styles.sectionHead}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          참여 가게
        </h2>
        <span className={styles.count}>{stores.length}곳</span>
      </div>
      <p className={styles.hint}>영업 상태 · 예약 받기 · 영업시간은 사장님이 바꿔요. 상인회는 지도 위치 · 주소 · 골목을 관리해요.</p>
      {stores.length === 0 ? (
        <p className={styles.empty}>아직 참여 가게가 없어요.</p>
      ) : (
        <ul className={styles.cards}>
          {stores.map((store) => (
            <li key={store.id}>
              <StoreCard store={store} onUpdated={onUpdated} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function StoreCard({ store, onUpdated }) {
  const [editing, setEditing] = useState(false);
  const withdrawing = store.joinStatus === 'withdraw_requested';

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <h3 className={styles.cardTitle}>
          <span className={styles.storeId}>#{store.id}</span> {store.name}
        </h3>
        <Badge tone={store.reservable ? 'confirmed' : 'canceled'}>{store.reservable ? '예약 가능' : '예약 불가'}</Badge>
      </div>
      {withdrawing && <p className={styles.warning}>사장님이 탈퇴를 신청했어요.</p>}

      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt>업종</dt>
          <dd>
            {categoryLabel(store.category)} · {alleyLabel(store.alley)}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>영업</dt>
          <dd>
            {BUSINESS_STATUS_LABELS[store.businessStatus] ?? store.businessStatus} · 예약 {store.reservationEnabled ? '받는 중' : '잠시 멈춤'}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>시간</dt>
          <dd>{formatHours(store)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>지도 좌표</dt>
          <dd>{formatMapPoint(store)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>주소</dt>
          <dd>{store.address ?? '미지정'}</dd>
        </div>
      </dl>

      {editing ? (
        <LocationForm
          store={store}
          onCancel={() => setEditing(false)}
          onSaved={(updated) => {
            setEditing(false);
            onUpdated(updated);
          }}
        />
      ) : (
        <Button variant="secondary" onClick={() => setEditing(true)}>
          위치 · 주소 고치기
        </Button>
      )}
    </article>
  );
}

/** 빈칸이면 null, 숫자면 범위 · 소수 넷째 자리 검사. 틀리면 undefined. */
function parseMapValue(text) {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  if (!/^-?\d{1,3}(\.\d{1,4})?$/.test(trimmed)) return undefined;
  const value = Number(trimmed);
  return Math.abs(value) <= LOCATION_LIMITS.mapMax ? value : undefined;
}

function LocationForm({ store, onCancel, onSaved }) {
  const id = useId();
  const [address, setAddress] = useState(store.address ?? '');
  const [alley, setAlley] = useState(store.alley);
  const [mapX, setMapX] = useState(store.mapX === null ? '' : String(store.mapX));
  const [mapY, setMapY] = useState(store.mapY === null ? '' : String(store.mapY));
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  async function submit(event) {
    event.preventDefault();
    const x = parseMapValue(mapX);
    const y = parseMapValue(mapY);
    const nextErrors = {};
    if (address.trim().length > LOCATION_LIMITS.addressMaxLength) nextErrors.address = `주소는 ${LOCATION_LIMITS.addressMaxLength}자까지예요.`;
    const coordinateHint = '-999.9999 ~ 999.9999, 소수 넷째 자리까지 넣거나 비워 두세요.';
    if (x === undefined) nextErrors.mapX = coordinateHint;
    if (y === undefined) nextErrors.mapY = coordinateHint;
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSaving(true);
    setError(null);
    try {
      const updated = await updateStoreLocation(store.id, { address: address.trim() || null, alley, mapX: x, mapY: y });
      onSaved(updated);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '저장하지 못했어요.');
      setSaving(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <TextField
        id={`${id}-address`}
        label="주소"
        value={address}
        onChange={(event) => setAddress(event.target.value)}
        maxLength={LOCATION_LIMITS.addressMaxLength}
        hint="비워 두면 주소 없음"
        error={errors.address}
      />
      <div className={styles.field}>
        <label htmlFor={`${id}-alley`} className={styles.label}>
          골목
        </label>
        <select id={`${id}-alley`} className={styles.select} value={alley} onChange={(event) => setAlley(event.target.value)}>
          {Object.entries(STORE_ALLEY_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <div className={styles.pair}>
        <TextField id={`${id}-x`} label="지도 X" inputMode="decimal" value={mapX} onChange={(event) => setMapX(event.target.value)} error={errors.mapX} />
        <TextField id={`${id}-y`} label="지도 Y" inputMode="decimal" value={mapY} onChange={(event) => setMapY(event.target.value)} error={errors.mapY} />
      </div>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <Button variant="secondary" onClick={onCancel} disabled={saving}>
          취소
        </Button>
        <Button type="submit" disabled={saving}>
          {saving ? '저장 중' : '저장'}
        </Button>
      </div>
    </form>
  );
}
