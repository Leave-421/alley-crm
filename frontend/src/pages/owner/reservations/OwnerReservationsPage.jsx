import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { getClock } from '@/api/demoClock.js';
import { getOwnerReservations } from '@/api/ownerReservations.js';
import Button from '@/components/Button/Button.jsx';
import ReservationCard from './ReservationCard.jsx';
import { addDays, formatDayLabel, formatTime, isDateKey, toDateKey } from './reservationFormat.js';
import styles from './OwnerReservations.module.css';

const AUTO_RULES =
  '신청은 예약 시각까지 승인하지 않으면 자동 거절돼요(예약금 100% 환불). 확정 예약은 예약일 다음 날까지 방문 처리나 취소가 없으면 자동 취소돼요(환불 없음).';

/**
 * 서버 기준 '지금'. 시연 도구가 켜진 서버는 시각을 앞당길 수 있어서(D18) 서버 시각을 먼저 묻고,
 * 시연 도구가 꺼져 있으면(404) 기기 시각을 쓴다.
 */
function useServerNow() {
  const [nowMs, setNowMs] = useState(null);
  useEffect(() => {
    let active = true;
    getClock()
      .then((clock) => Date.parse(clock.now))
      .catch(() => Date.now())
      .then((ms) => {
        if (active) setNowMs(Number.isNaN(ms) ? Date.now() : ms);
      });
    return () => {
      active = false;
    };
  }, []);
  return nowMs;
}

/**
 * O-01 예약 관리(B6): 날짜별 예약 목록과 상태(신청 · 확정 · 방문 · 취소 + 사유), '추천으로 왔어요' 여부.
 * 기준: 07-screens O-01, FR-RSV-02 · 04, FR-CUS-05, CONTRACTS_API.md 「사장님 예약 (D3 · D4)」.
 * 신청 예약의 승인 · 거절(B7, FR-RSV-03)은 카드에 있다. 방문 처리 · 쿠폰 사용 확인은 B8, 실시간 반영 · 지연 표시는 B9에서 더한다.
 */
export default function OwnerReservationsPage() {
  const nowMs = useServerNow();
  const [searchParams, setSearchParams] = useSearchParams();
  const todayKey = nowMs === null ? null : toDateKey(nowMs);
  const requested = searchParams.get('date');
  const dateKey = isDateKey(requested) ? requested : todayKey;

  const [reloadCount, setReloadCount] = useState(0);
  // 응답은 어느 요청(날짜 + 다시 불러오기 횟수)의 결과인지와 같이 둔다. 지금 요청의 결과가 아니면 불러오는 중이다.
  const requestKey = dateKey ? `${dateKey}#${reloadCount}` : null;
  const [result, setResult] = useState({ key: null, status: 'ready', items: [], error: null });
  const state = result.key === requestKey ? result : { status: 'loading', items: [], error: null };

  useEffect(() => {
    if (!requestKey) return undefined;
    let active = true; // 날짜를 빨리 넘기면 늦게 온 앞 날짜 응답을 버린다
    getOwnerReservations(requestKey.split('#')[0])
      .then((response) => {
        if (active) setResult({ key: requestKey, status: 'ready', items: response.items, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: requestKey, status: 'error', items: [], error });
      });
    return () => {
      active = false;
    };
  }, [requestKey]);

  const [notice, setNotice] = useState(null);

  function moveTo(nextKey) {
    setNotice(null);
    setSearchParams(nextKey === todayKey ? {} : { date: nextKey });
  }

  /**
   * 승인 · 거절(B7) · 방문 처리(B8) 결과를 목록에 반영한다. 응답 Reservation에는 손님 이름 · 회원 여부가 없어
   * 상태 · 방문 칸만 바꾼다. 쿠폰 사용 확인처럼 예약이 바뀌지 않으면 reservation 없이 안내만 한다.
   */
  function handleDecided(reservation, message) {
    setNotice(message);
    if (!reservation) return;
    const { id, status, cancelReason, visitAmount = null, visitedAt = null } = reservation;
    setResult((previous) => ({
      ...previous,
      items: previous.items.map((item) => (item.id === id ? { ...item, status, cancelReason, visitAmount, visitedAt } : item)),
    }));
  }

  /** 이미 처리됐거나(409 · 404) 자동 거절된 예약: 안내하고 서버 기준으로 다시 읽는다. */
  function handleOutdated(message) {
    setNotice(message);
    setReloadCount((count) => count + 1);
  }

  if (!dateKey) {
    return (
      <main className={styles.page}>
        <p role="status">예약을 불러오고 있어요.</p>
      </main>
    );
  }

  const isToday = dateKey === todayKey;
  const items = state.status === 'ready' ? state.items : [];
  const active = items.filter((item) => item.status !== 'canceled');
  const waiting = items.filter((item) => item.status === 'requested').length;
  const visited = items.filter((item) => item.status === 'visited').length;
  const remaining = items.filter((item) => item.status === 'requested' || item.status === 'confirmed').length;

  return (
    <main className={styles.page}>
      <header className={styles.dayHead}>
        <div className={styles.dayNav}>
          <button type="button" className={styles.navButton} onClick={() => moveTo(addDays(dateKey, -1))} aria-label="이전 날">
            ‹
          </button>
          <h1 className={styles.dayTitle}>
            {isToday && <span className={styles.todayChip}>오늘</span>}
            {formatDayLabel(dateKey)}
          </h1>
          <button type="button" className={styles.navButton} onClick={() => moveTo(addDays(dateKey, 1))} aria-label="다음 날">
            ›
          </button>
        </div>
        {!isToday && (
          <Button variant="secondary" onClick={() => moveTo(todayKey)}>
            오늘로
          </Button>
        )}
      </header>

      {state.status === 'ready' && (
        <p className={styles.summary}>
          {isToday && `오늘 ${formatTime(nowMs)} 기준 · `}예약 {active.length}건 · 신청 대기 {waiting}건
        </p>
      )}

      <p className={styles.rules}>{AUTO_RULES}</p>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {state.status === 'ready' && (
        <dl className={styles.kpis}>
          <div className={styles.kpi}>
            <dt>방문 완료</dt>
            <dd>{visited}건</dd>
          </div>
          <div className={styles.kpi}>
            <dt>남은 예약</dt>
            <dd>{remaining}건</dd>
          </div>
        </dl>
      )}

      {state.status === 'loading' && <p role="status">예약을 불러오고 있어요.</p>}

      {state.status === 'error' && (
        <section className={styles.message} role="alert">
          <p>예약을 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
            다시 불러오기
          </Button>
        </section>
      )}

      {state.status === 'ready' &&
        (items.length === 0 ? (
          <p className={styles.message}>이 날은 예약이 없어요.</p>
        ) : (
          <ol className={styles.list} aria-label={`${formatDayLabel(dateKey)} 예약`}>
            {items.map((item) => (
              <li key={item.id}>
                <ReservationCard reservation={item} onDecided={handleDecided} onOutdated={handleOutdated} />
              </li>
            ))}
          </ol>
        ))}
    </main>
  );
}
