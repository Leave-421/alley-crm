import { useCallback, useEffect, useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { advanceClock, getClock, resetClock, setClock } from '@/api/demoClock.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import Button from '@/components/Button/Button.jsx';
import styles from './DemoTools.module.css';

const MINUTE = 1;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// 서버의 시간 규칙을 시연할 때 자주 쓰는 앞당기기. 규칙 자체는 서버가 판단한다(FR-RSV-02 · 03, FR-CPN-04 · 05).
const PRESETS = [
  { minutes: HOUR, label: '+1시간', hint: '예약 시각 넘기기' },
  { minutes: DAY, label: '+1일', hint: '다음 날로' },
  { minutes: 7 * DAY, label: '+7일', hint: '쿠폰 기본 기간(7일)' },
  { minutes: 30 * DAY, label: '+30일', hint: '쿠폰 재발급 제한(30일)' },
];

/** 서버 시각 조작(D18). 로그인한 누구나 쓸 수 있고, 서버가 시연 도구를 켰을 때만 있다. */
export default function DemoClockPanel() {
  const id = useId();
  const session = useSession();
  const loggedIn = session.status === SESSION_STATUS.AUTHENTICATED;
  const [clock, setClockState] = useState(null);
  const [problem, setProblem] = useState(null); // 'login' | 'disabled' | 메시지
  const [busy, setBusy] = useState(false);
  const [at, setAt] = useState('');

  const handleError = useCallback((error) => {
    if (error instanceof ApiError && error.status === 401) setProblem('login');
    else if (error instanceof ApiError && error.status === 404) setProblem('disabled');
    else setProblem(error?.message ?? '서버 시각을 바꾸지 못했어요.');
  }, []);

  useEffect(() => {
    if (!loggedIn) return undefined;
    let active = true;
    getClock()
      .then((response) => {
        if (!active) return;
        setClockState(response);
        setProblem(null);
      })
      .catch((error) => {
        if (active) handleError(error);
      });
    return () => {
      active = false;
    };
  }, [loggedIn, session.account?.id, handleError]);

  async function run(action) {
    setBusy(true);
    setProblem(null);
    try {
      setClockState(await action());
    } catch (error) {
      handleError(error);
    } finally {
      setBusy(false);
    }
  }

  function applyAt(event) {
    event.preventDefault();
    if (!at) return;
    // 입력은 한국 시각으로 받고, 계약대로 UTC ISO로 보낸다
    run(() => setClock(new Date(`${at}:00+09:00`).toISOString()));
  }

  return (
    <section className={styles.card} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        서버 시각
      </h2>
      <p className={styles.hint}>자동 거절 · 자동 취소 · 쿠폰 만료 · 리마인드는 모두 서버 시각을 따라요. 앞당기면 바로 확인할 수 있어요.</p>

      {(!loggedIn || problem === 'login') && <p className={styles.notice}>역할을 골라 로그인하면 쓸 수 있어요.</p>}
      {problem === 'disabled' && (
        <p className={styles.notice}>서버의 시연 도구가 꺼져 있어요. 백엔드를 DEMO_TOOLS_ENABLED=true로 켜야 해요.</p>
      )}
      {problem && problem !== 'login' && problem !== 'disabled' && (
        <p className={styles.error} role="alert">
          {problem}
        </p>
      )}

      {loggedIn && clock && problem !== 'disabled' && (
        <>
          <dl className={styles.clock}>
            <div>
              <dt>지금 서버 시각</dt>
              <dd className={styles.clockNow}>{formatKst(clock.now)}</dd>
            </div>
            <div>
              <dt>실제 시각과 차이</dt>
              <dd>{describeOffset(clock.offsetSeconds)}</dd>
            </div>
          </dl>

          <ul className={styles.presets}>
            {PRESETS.map((preset) => (
              <li key={preset.label}>
                <Button variant="secondary" block disabled={busy} onClick={() => run(() => advanceClock(preset.minutes))}>
                  {preset.label}
                </Button>
                <span className={styles.presetHint}>{preset.hint}</span>
              </li>
            ))}
          </ul>

          <form className={styles.atForm} onSubmit={applyAt}>
            <label htmlFor={`${id}-at`} className={styles.label}>
              날짜 · 시각으로 맞추기(한국 시각)
            </label>
            <div className={styles.atRow}>
              <input
                id={`${id}-at`}
                className={styles.input}
                type="datetime-local"
                value={at}
                onChange={(event) => setAt(event.target.value)}
              />
              <Button type="submit" disabled={busy || !at}>
                맞추기
              </Button>
            </div>
          </form>

          <Button variant="danger" block disabled={busy || clock.offsetSeconds === 0} onClick={() => run(resetClock)}>
            실제 시각으로 되돌리기
          </Button>
        </>
      )}
    </section>
  );
}

function formatKst(iso) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

function describeOffset(seconds) {
  if (seconds === 0) return '없음 (실제 시각)';
  const total = Math.abs(Math.round(seconds / 60));
  const days = Math.floor(total / DAY);
  const hours = Math.floor((total % DAY) / HOUR);
  const minutes = total % HOUR;
  const parts = [days && `${days}일`, hours && `${hours}시간`, minutes && `${minutes}분`].filter(Boolean);
  return `${parts.join(' ')} ${seconds > 0 ? '빠름' : '느림'}`;
}
