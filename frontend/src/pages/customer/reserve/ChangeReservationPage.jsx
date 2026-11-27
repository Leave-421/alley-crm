import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { ApiError } from '@/api/client.js';
import { changeReservation, getAvailableSlots, getMyReservations } from '@/api/reservations.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import { formatDateKey, formatSlotTime, serviceDateKey, upcomingDays } from './reserveTime.js';
import styles from './ReservePage.module.css';

const DATE_CHOICES = 7;
const PARTY_FALLBACK_MAX = 8;
const won = (amount) => `${amount.toLocaleString('ko-KR')}원`;
const whenText = (iso, party) => `${formatDateKey(serviceDateKey(Date.parse(iso)))} ${formatSlotTime(iso)} · ${party}명`;

function changeErrorMessage(error) {
  switch (error instanceof ApiError ? error.code : null) {
    case 'conflict':
      return '지금은 변경할 수 없어요. 확정된 예약만, 가게 취소 마감 전까지, 한 번에 하나의 변경만 요청할 수 있어요.';
    case 'slot_unavailable':
      return '그 시간은 방금 다 찼거나 예약을 받을 수 없게 됐어요. 다른 시간을 골라 주세요.';
    case 'insufficient_balance':
      return '인원이 늘어 더 낼 예약금보다 가상 잔액이 적어요.';
    case 'invalid':
      return '바뀐 내용을 확인해 주세요.';
    default:
      return error?.message || '변경을 요청하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
  }
}

/**
 * 예약 변경(G-05, 회원). 내 예약(G-04)의 '변경'에서 들어온다. 새 일시 · 인원을 고르면 변경 요청을 보내고,
 * 예약은 다시 '신청'이 되어 사장님 승인을 기다린다(거절되면 원래 예약 유지). 인원이 바뀌면 예약금 차액은 승인될 때 정산된다.
 */
export default function ChangeReservationPage() {
  const { reservationId } = useParams();
  const { status, role } = useSession();
  const isMember = status === SESSION_STATUS.AUTHENTICATED && role === 'customer';
  const [loaded, setLoaded] = useState(null); // { reservation | null } | { error }

  useEffect(() => {
    if (!isMember) return undefined;
    let active = true;
    getMyReservations()
      .then((data) => {
        const reservation = data.items.flatMap((group) => group.reservations).find((item) => item.id === reservationId) ?? null;
        if (active) setLoaded({ reservation });
      })
      .catch((error) => active && setLoaded({ error }));
    return () => {
      active = false;
    };
  }, [isMember, reservationId]);

  if (status === SESSION_STATUS.LOADING || (isMember && !loaded)) return <main className={styles.page} />;

  const reservation = loaded?.reservation;
  // 변경은 확정 예약만, 가게 취소 마감 전까지(취소 가능 여부와 같은 기한), 이미 변경 대기 중이면 불가(D8)
  const changeable = reservation && reservation.status === 'confirmed' && reservation.cancelable && !reservation.pendingStartAt && !reservation.pendingPartySize;
  if (!isMember || !changeable) {
    let message = '로그인하면 내 예약을 변경할 수 있어요.';
    if (isMember && loaded?.error) message = '예약을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.';
    else if (isMember && !reservation) message = '예약을 찾을 수 없어요.';
    else if (isMember) message = '이 예약은 지금 변경할 수 없어요. 확정된 예약만, 가게 취소 마감 전까지 변경할 수 있어요.';
    return (
      <main className={styles.page}>
        <section className={styles.card}>
          <p className={styles.notice}>{message}</p>
          <Link to="/reservations" className={`${buttonStyles.button} ${buttonStyles.secondary} ${styles.linkButton}`}>
            내 예약 보기
          </Link>
        </section>
      </main>
    );
  }
  return <ChangeForm reservation={reservation} />;
}

function ChangeForm({ reservation }) {
  const [days] = useState(() => upcomingDays(DATE_CHOICES));
  const [dateKey, setDateKey] = useState(() => {
    const original = serviceDateKey(Date.parse(reservation.startAt));
    return days.some((day) => day.key === original) ? original : days[0].key;
  });
  const [slotStartAt, setSlotStartAt] = useState(null); // null = 시각은 그대로
  const [party, setParty] = useState(reservation.partySize);
  const [availability, setAvailability] = useState(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const [result, setResult] = useState(null);

  useEffect(() => {
    let active = true;
    const key = `${dateKey}#${reloadCount}`;
    getAvailableSlots(reservation.storeId, dateKey)
      .then((data) => active && setAvailability({ key, data }))
      .catch((error) => active && setAvailability({ key, error }));
    return () => {
      active = false;
    };
  }, [reservation.storeId, dateKey, reloadCount]);

  if (result) {
    return (
      <main className={styles.page}>
        <div className={styles.doneIcon} aria-hidden="true">
          ✓
        </div>
        <h1 className={`${styles.title} ${styles.doneTitle}`}>변경 요청을 보냈어요</h1>
        <section className={`${styles.card} ${styles.doneCard}`} aria-label="요청한 변경">
          <div className={styles.doneStore}>{reservation.storeName}</div>
          <div className={styles.doneRow}>
            <span>요청한 날짜</span>
            <b className={styles.num}>{formatDateKey(serviceDateKey(Date.parse(result.pendingStartAt ?? reservation.startAt)))}</b>
          </div>
          <div className={styles.doneRow}>
            <span>요청한 시간</span>
            <b className={styles.num}>{formatSlotTime(result.pendingStartAt ?? reservation.startAt)}</b>
          </div>
          <div className={styles.doneRow}>
            <span>인원</span>
            <b className={styles.num}>{result.pendingPartySize ?? reservation.partySize}명</b>
          </div>
          <span className={`${styles.badge} ${styles.statusBadge}`}>가게 확인 대기</span>
        </section>
        <div className={styles.info}>가게가 확인하면 알림으로 알려드려요. 거절되면 원래 예약({whenText(reservation.startAt, reservation.partySize)})이 그대로 유지돼요.</div>
        <Link to="/reservations" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.linkButton}`}>
          내 예약 보기
        </Link>
      </main>
    );
  }

  const current = availability?.key === `${dateKey}#${reloadCount}` ? availability : null;
  const slotsData = current?.data ?? null;
  const maxParty = slotsData?.maxPartySize ?? PARTY_FALLBACK_MAX;
  const perPerson = slotsData?.depositPerPerson ?? 0;
  const diff = perPerson * (party - reservation.partySize);
  const timeChanged = Boolean(slotStartAt) && slotStartAt !== reservation.startAt;
  const partyChanged = party !== reservation.partySize;

  let blockedReason = null;
  if (party > maxParty) blockedReason = `한 팀 최대 ${maxParty}명까지 예약할 수 있어요`;
  else if (!timeChanged && !partyChanged) blockedReason = '바꿀 날짜 · 시간이나 인원을 골라 주세요';

  async function submit() {
    if (blockedReason || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      setResult(await changeReservation(reservation.id, { startAt: timeChanged ? slotStartAt : null, partySize: partyChanged ? party : null }));
    } catch (error) {
      setSubmitError(error);
      if (error instanceof ApiError && error.code === 'slot_unavailable') {
        setSlotStartAt(null);
        setReloadCount((count) => count + 1);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className={styles.page}>
      <span className={styles.badge}>예약 변경</span>
      <h1 className={styles.title}>{reservation.storeName}</h1>
      <div className={styles.info}>현재 예약 · {whenText(reservation.startAt, reservation.partySize)}</div>

      <div className={styles.layout}>
        <div>
          <section className={styles.card} aria-labelledby="change-date">
            <h2 id="change-date" className={styles.label}>
              날짜 · {DATE_CHOICES}일 안에서 선택
            </h2>
            <div className={styles.dates}>
              {days.map((day) => (
                <button
                  key={day.key}
                  type="button"
                  className={day.key === dateKey ? `${styles.date} ${styles.dateOn}` : styles.date}
                  aria-pressed={day.key === dateKey}
                  aria-label={`${day.day}일 ${day.weekday}요일`}
                  onClick={() => {
                    setDateKey(day.key);
                    setSlotStartAt(null);
                  }}
                >
                  <span className={styles.dateWeekday}>{day.weekday}</span>
                  <span className={styles.dateDay}>{day.day}</span>
                </button>
              ))}
            </div>
          </section>

          <section className={styles.card} aria-labelledby="change-time">
            <h2 id="change-time" className={styles.label}>
              시간 · 고르지 않으면 지금 시간 그대로
            </h2>
            {!current && <p className={styles.notice}>예약 가능한 시간을 불러오는 중이에요…</p>}
            {current?.error && <p className={styles.notice}>예약 가능한 시간을 불러오지 못했어요.</p>}
            {slotsData?.slots.length === 0 && <p className={styles.notice}>이 날은 쉬는 날이에요.</p>}
            {slotsData?.slots.length > 0 && (
              <div className={styles.slots}>
                {slotsData.slots.map((slot) => {
                  const isCurrent = slot.startAt === reservation.startAt;
                  const on = slot.startAt === slotStartAt;
                  const usable = slot.available && !isCurrent;
                  const classes = [styles.slot, on && styles.slotOn, !usable && styles.slotOff].filter(Boolean).join(' ');
                  return (
                    <button key={slot.startAt} type="button" className={classes} aria-pressed={on} disabled={!usable} onClick={() => setSlotStartAt(slot.startAt)}>
                      <b className={styles.num}>{formatSlotTime(slot.startAt)}</b>
                      <span className={styles.slotSub}>{isCurrent ? '지금 예약' : !slot.available ? '마감' : on ? '선택' : '예약 가능'}</span>
                    </button>
                  );
                })}
              </div>
            )}
          </section>

          <section className={`${styles.card} ${styles.partyRow}`} aria-labelledby="change-party">
            <div>
              <h2 id="change-party" className={styles.label}>
                인원{slotsData?.maxPartySize ? ` · 한 팀 최대 ${slotsData.maxPartySize}명` : ''}
              </h2>
              <div className={`${styles.partyCount} ${styles.num}`} aria-live="polite">
                {party}명
              </div>
            </div>
            <div className={styles.partyButtons}>
              <button type="button" className={styles.partyButton} aria-label="인원 줄이기" disabled={party <= 1} onClick={() => setParty((n) => Math.max(1, n - 1))}>
                −
              </button>
              <button type="button" className={styles.partyButton} aria-label="인원 늘리기" disabled={party >= maxParty} onClick={() => setParty((n) => Math.min(maxParty, n + 1))}>
                +
              </button>
            </div>
          </section>
        </div>

        <div>
          <section className={styles.card} aria-label="변경 안내">
            <p className={styles.cap}>변경하면 다시 사장님 승인이 필요하고, 거절되면 원래 예약이 그대로 유지돼요.</p>
            <p className={styles.cap}>가게 취소 마감 전까지만 변경할 수 있어요.</p>
            {diff !== 0 && (
              <p className={styles.cap} aria-live="polite">
                인원이 바뀌어 승인되면 예약금 {won(Math.abs(diff))}을 {diff > 0 ? '더 내요' : '돌려받아요'}. 승인 전에는 돈이 움직이지 않아요.
              </p>
            )}
          </section>

          {submitError && (
            <div className={styles.error} role="alert">
              {changeErrorMessage(submitError)}
              {submitError instanceof ApiError && submitError.code === 'insufficient_balance' && (
                <Link to="/my" className={styles.errorLink}>
                  마이에서 충전하기
                </Link>
              )}
            </div>
          )}

          {blockedReason ? (
            <div className={styles.blocked} role="status">
              {blockedReason}
            </div>
          ) : (
            <button type="button" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.submit}`} disabled={submitting} onClick={submit}>
              {submitting ? '보내는 중…' : '변경 요청 보내기'}
            </button>
          )}
        </div>
      </div>
    </main>
  );
}
