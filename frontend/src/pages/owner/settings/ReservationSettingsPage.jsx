import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { getMyStore } from '@/api/ownerStore.js';
import { getReservationSettings } from '@/api/reservationSettings.js';
import Button from '@/components/Button/Button.jsx';
import ReservationSettingsForm from './ReservationSettingsForm.jsx';
import StoreStatusForm from './StoreStatusForm.jsx';
import styles from './ReservationSettings.module.css';

/**
 * O-03 가게 · 예약 설정(B10). 기준: 07-screens O-03, FR-STO-02, FR-RSV-05 · 06 · 08,
 * CONTRACTS_API.md 「사장님 내 가게 (C22)」 · 「예약 운영 설정 · 슬롯 (D5)」.
 * 위쪽은 영업 상태 · 예약 받기(C22), 아래쪽은 예약 운영 설정(D5). 영업시간 · 휴무일은 O-12(B24)에서 정한다.
 */
export default function ReservationSettingsPage() {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', store: null, settings: null, error: null });
  const [notice, setNotice] = useState(null);
  const state = result.key === reloadCount ? result : { status: 'loading', store: null, settings: null, error: null };

  useEffect(() => {
    let active = true;
    Promise.all([getMyStore(), getReservationSettings()])
      .then(([store, settings]) => {
        if (active) setResult({ key: reloadCount, status: 'ready', store, settings, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', store: null, settings: null, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  function handleStoreSaved(store) {
    setNotice(store.reservable ? '영업 상태를 저장했어요. 지금 예약을 받고 있어요.' : '영업 상태를 저장했어요. 새 예약은 받지 않고, 이미 받은 예약은 그대로예요.');
    setResult((previous) => ({ ...previous, store }));
  }

  function handleSettingsSaved(settings) {
    setNotice('저장했어요. 바뀐 설정은 이제부터 들어오는 신청에 적용되고, 이미 받은 예약은 그대로예요.');
    setResult((previous) => ({ ...previous, settings }));
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>가게 · 예약 설정</h1>
        <p className={styles.lead}>
          영업 상태와 예약 받는 방식을 가게에 맞게 정해요. 영업시간 · 휴무일은 <Link to="/owner/store">가게 정보</Link>에서 정해요.
        </p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {state.status === 'loading' && <p role="status">설정을 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>설정을 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
            다시 불러오기
          </Button>
        </section>
      )}
      {state.status === 'ready' && (
        <>
          <StoreStatusForm key={`${state.store.businessStatus}-${state.store.reservationEnabled}`} store={state.store} onSaved={handleStoreSaved} />
          <ReservationSettingsForm settings={state.settings} onSaved={handleSettingsSaved} />
        </>
      )}
    </main>
  );
}
