import { useCallback, useEffect, useState } from 'react';
import { getBenefitTypes, getMyCouponEvent } from '@/api/couponEvents.js';
import Button from '@/components/Button/Button.jsx';
import CouponEventForm from './CouponEventForm.jsx';
import CouponEventSummary from './CouponEventSummary.jsx';
import styles from './CouponSettings.module.css';

/**
 * O-09 쿠폰 설정(B15). 진행 중인 행사가 있으면 확정된 내용과 중단, 없으면 새 행사 만들기.
 * 기준: 07-screens O-09, FR-CPN-01 · 11, 결정 11-25, CONTRACTS_API.md 3.2.
 * 쿠폰 지표(FR-ANL-09)는 B22(여유)에서 더한다.
 */
export default function CouponSettingsPage() {
  const [state, setState] = useState({ status: 'loading', current: null, benefitTypes: [], error: null });
  const [notice, setNotice] = useState(null);

  const load = useCallback(() => {
    return Promise.all([getMyCouponEvent(), getBenefitTypes()])
      .then(([mine, types]) => {
        setState({ status: 'ready', current: mine.current, benefitTypes: types.items, error: null });
      })
      .catch((error) => {
        setState((previous) => ({ ...previous, status: 'error', error }));
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function retry() {
    setState((previous) => ({ ...previous, status: 'loading' }));
    load();
  }

  function handleCreated(event) {
    setNotice('쿠폰 행사를 확정했어요. 추천 카드에 ‘예약 혜택’이 표시돼요.');
    setState((previous) => ({ ...previous, current: event }));
  }

  function handleStopped() {
    setNotice('행사를 중단했어요. 새 발급만 멈추고, 이미 받은 쿠폰은 자기 기한까지 쓸 수 있어요.');
    setState((previous) => ({ ...previous, current: null }));
  }

  /** 다른 창에서 바뀌었을 때(409 · 404) 서버 기준으로 다시 읽는다. */
  function handleOutdated(message) {
    setNotice(message);
    load();
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>다음 방문을 만드는 혜택</h1>
        <p className={styles.lead}>추천 카드에서 손님이 받아 가는 우리 가게 쿠폰이에요. 진행 중인 행사는 하나만 둘 수 있어요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {state.status === 'loading' && <p role="status">쿠폰 행사를 불러오고 있어요.</p>}

      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>쿠폰 행사를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={retry}>
            다시 불러오기
          </Button>
        </section>
      )}

      {state.status === 'ready' &&
        (state.current ? (
          <CouponEventSummary event={state.current} onStopped={handleStopped} onOutdated={handleOutdated} />
        ) : (
          <CouponEventForm benefitTypes={state.benefitTypes} onCreated={handleCreated} onOutdated={handleOutdated} />
        ))}
    </main>
  );
}
