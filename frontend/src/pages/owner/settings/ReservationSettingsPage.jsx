import { useEffect, useState } from 'react';
import { getReservationSettings } from '@/api/reservationSettings.js';
import Button from '@/components/Button/Button.jsx';
import ReservationSettingsForm from './ReservationSettingsForm.jsx';
import styles from './ReservationSettings.module.css';

/**
 * O-03 가게 · 예약 설정(B10). 기준: 07-screens O-03, FR-RSV-05 · 06 · 08, CONTRACTS_API.md D5.
 * 영업 상태 · 예약 받기(FR-STO-02)는 C22 API가 main에 들어오면 이 화면 위쪽에 더한다.
 * 영업시간 · 휴무일은 O-12(B24)에서 정한다.
 */
export default function ReservationSettingsPage() {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', settings: null, error: null });
  const [notice, setNotice] = useState(null);
  const state = result.key === reloadCount ? result : { status: 'loading', settings: null, error: null };

  useEffect(() => {
    let active = true;
    getReservationSettings()
      .then((settings) => {
        if (active) setResult({ key: reloadCount, status: 'ready', settings, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', settings: null, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  function handleSaved(settings) {
    setNotice('저장했어요. 바뀐 설정은 이제부터 들어오는 신청에 적용되고, 이미 받은 예약은 그대로예요.');
    setResult((previous) => ({ ...previous, settings }));
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>예약 운영 설정</h1>
        <p className={styles.lead}>슬롯 · 받는 팀 수 · 예약 기간 · 취소 마감을 가게에 맞게 정해요. 영업시간 · 휴무일은 가게 정보에서 정해요.</p>
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
      {state.status === 'ready' && state.settings && <ReservationSettingsForm settings={state.settings} onSaved={handleSaved} />}
    </main>
  );
}
