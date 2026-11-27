import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import { getMyStore, updateMyStoreHours } from '@/api/ownerStore.js';
import { WEEKDAY_LABELS } from '@/api/stores.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { alleyLabel, categoryLabel, formatHours, formatMapPoint } from '@/pages/association/stores/storeFormat.js';
import styles from '@/pages/owner/settings/ReservationSettings.module.css';

const WEEKDAYS = Object.keys(WEEKDAY_LABELS);

/**
 * O-12 가게 정보 관리(B24). 기준: 07-screens O-12, FR-STO-02 · FR-RSV-05, CONTRACTS_API.md 「사장님 내 가게 (C22)」.
 * - 영업 시작 · 종료와 휴무일을 사장님이 바꾼다. 예약 가능 시간의 기준이 된다(D5 슬롯 계산)
 * - 주소 · 지도 위치는 상인회가 관리해서 보기만 한다(C6 PUT /stores/{id}/location)
 * - 한 줄 소개 · 대표 메뉴 · 편의 정보 · 좌석 · 가격대 · 사진(여유)은 계약에 칸이 없어 넣지 않았다
 */
export default function StoreInfoPage() {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', store: null, error: null });
  const [notice, setNotice] = useState(null);
  const state = result.key === reloadCount ? result : { status: 'loading', store: null, error: null };

  useEffect(() => {
    let active = true;
    getMyStore()
      .then((store) => {
        if (active) setResult({ key: reloadCount, status: 'ready', store, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', store: null, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  function handleSaved(store) {
    setNotice(`저장했어요 · ${formatHours(store)}. 손님 가게 화면과 예약 가능 시간에 바로 반영돼요. 이미 받은 예약은 그대로예요.`);
    setResult((previous) => ({ ...previous, store }));
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>가게 정보</h1>
        <p className={styles.lead}>손님이 가게를 눌렀을 때 보이는 정보예요. 영업시간 · 휴무일은 예약 가능 시간의 기준이 돼요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {state.status === 'loading' && <p role="status">가게 정보를 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>가게 정보를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
            다시 불러오기
          </Button>
        </section>
      )}
      {state.status === 'ready' && (
        <>
          <HoursForm key={`${state.store.openTime}-${state.store.closeTime}-${state.store.closedDays.join()}`} store={state.store} onSaved={handleSaved} />
          <LocationView store={state.store} />
          <nav className={styles.hint} aria-label="가게 · 예약 설정 바로가기">
            <Link to="/owner/settings">예약 운영 설정 ›</Link>
          </nav>
        </>
      )}
    </main>
  );
}

/** 영업 시작 · 종료(HH:mm, 시작 < 종료)와 휴무일. 휴무일을 안 고르면 연중무휴. */
function HoursForm({ store, onSaved }) {
  const id = useId();
  const [openTime, setOpenTime] = useState(store.openTime ?? '');
  const [closeTime, setCloseTime] = useState(store.closeTime ?? '');
  const [closedDays, setClosedDays] = useState(store.closedDays ?? []);
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const sortedDays = WEEKDAYS.filter((day) => closedDays.includes(day));
  const changed = openTime !== store.openTime || closeTime !== store.closeTime || sortedDays.join() !== (store.closedDays ?? []).join();

  function toggleDay(day) {
    setClosedDays((previous) => (previous.includes(day) ? previous.filter((item) => item !== day) : [...previous, day]));
  }

  async function submit(event) {
    event.preventDefault();
    const found = {};
    if (!openTime) found.openTime = '영업 시작 시각을 넣어 주세요.';
    if (!closeTime) found.closeTime = '영업 종료 시각을 넣어 주세요.';
    else if (openTime && openTime >= closeTime) found.closeTime = '영업 종료는 시작보다 늦어야 해요.';
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    setError(null);
    try {
      onSaved(await updateMyStoreHours({ openTime, closeTime, closedDays: sortedDays }));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '저장하지 못했어요.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        영업시간 · 휴무일
      </h2>
      <div className={styles.pair}>
        <TextField id={`${id}-open`} label="영업 시작" type="time" value={openTime} error={errors.openTime} onChange={(event) => setOpenTime(event.target.value)} />
        <TextField id={`${id}-close`} label="영업 종료" type="time" value={closeTime} error={errors.closeTime} onChange={(event) => setCloseTime(event.target.value)} />
      </div>
      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>휴무일</legend>
        <div className={styles.choices}>
          {WEEKDAYS.map((day) => (
            <label key={day} className={styles.choice}>
              <input type="checkbox" checked={closedDays.includes(day)} onChange={() => toggleDay(day)} />
              {WEEKDAY_LABELS[day]}
            </label>
          ))}
        </div>
        <p className={styles.hint}>{sortedDays.length === 0 ? '고르지 않으면 연중무휴예요.' : '고른 요일에는 예약을 받지 않아요.'}</p>
      </fieldset>
      <p className={styles.hint}>예약 가능 시간은 영업 시작부터 종료까지 끝나는 슬롯이에요. 바꿔도 이미 받은 예약은 그대로예요.</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <Button type="submit" disabled={saving || !changed} block>
        {saving ? '저장 중' : changed ? '영업시간 저장' : '바뀐 내용 없음'}
      </Button>
    </form>
  );
}

/** 주소 · 지도 위치는 보기만(상인회가 관리, FR-STO-02 #3). */
function LocationView({ store }) {
  const id = useId();
  return (
    <section className={styles.card} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        {store.name}
      </h2>
      <dl className={styles.facts}>
        <div>
          <dt>업종 · 골목</dt>
          <dd>
            {categoryLabel(store.category)} · {alleyLabel(store.alley)}
          </dd>
        </div>
        <div>
          <dt>주소</dt>
          <dd>{store.address ?? '아직 없음'}</dd>
        </div>
        <div>
          <dt>지도 위치</dt>
          <dd>{formatMapPoint(store)}</dd>
        </div>
      </dl>
      <p className={styles.warning}>가게 위치와 주소는 상인회가 관리해요. 바꾸려면 상인회에 요청해 주세요.</p>
    </section>
  );
}
