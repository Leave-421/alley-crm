import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { logout } from '@/api/auth.js';
import { ApiError } from '@/api/client.js';
import { getGuestReservations, guestLookup } from '@/api/guest.js';
import { getNotifications } from '@/api/notifications.js';
import { CANCEL_REASON_LABELS, RESERVATION_STATUS_LABELS } from '@/api/reservations.js';
import { ANONYMOUS_SESSION, useSession } from '@/auth/session.js';
import { CATEGORY_LABELS, MOCK_STORES } from '@/map/storeCatalog.js';
import { formatDateKey, formatSlotTime, serviceDateKey } from '../reserve/reserveTime.js';
import formStyles from '../reserve/ReservePage.module.css';
import styles from '../trips/MyReservationsPage.module.css';

const RESERVATION_NO_LENGTH = 8;
const isUpcoming = (reservation, nowMs) => (reservation.status === 'requested' || reservation.status === 'confirmed') && Date.parse(reservation.startAt) > nowMs;
const isChangePending = (reservation) => Boolean(reservation.pendingStartAt || reservation.pendingPartySize);

/**
 * 비회원 예약 조회(G-13, FR-AUTH-03). 예약번호 + 전화번호가 맞으면 그 전화번호의 비회원 예약만 다루는 제한 세션(C13)으로 들어가
 * 예약(G-04와 같은 요소)과 알림을 본다. 번호가 맞지 않으면 들어가지 못한다(어느 쪽이 틀렸는지는 알려 주지 않음).
 * 제한 세션은 새로고침해도 서버에 남아 있어서, 열 때 목록부터 불러 보고 401 · 403이면 입력 화면을 보여준다.
 */
export default function GuestReservationPage() {
  const { setSession } = useSession();
  const [view, setView] = useState({ status: 'checking' }); // checking | form | entered { items, unread } | error
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;
    Promise.all([getGuestReservations(), getNotifications({ page: 0, size: 1 }).catch(() => null)])
      .then(([data, notices]) => active && setView({ status: 'entered', items: data.items, unread: notices?.unreadCount ?? 0 }))
      .catch((error) => {
        if (!active) return;
        const notEntered = error instanceof ApiError && (error.status === 401 || error.status === 403);
        setView(notEntered ? { status: 'form' } : { status: 'error' });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  async function leave() {
    try {
      await logout();
    } catch (error) {
      console.warn('비회원 조회 세션을 끝내지 못했어요.', error);
    }
    setView({ status: 'form' });
  }

  if (view.status === 'checking') return <main className={styles.page} />;
  if (view.status === 'error') {
    return (
      <main className={styles.page}>
        <p className={styles.cap} role="alert">
          예약을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.
        </p>
      </main>
    );
  }
  if (view.status === 'form') {
    return (
      <LookupForm
        onEntered={() => {
          setSession(ANONYMOUS_SESSION); // 계정 세션이었다면 제한 세션으로 바뀌었다(C13)
          setReloadCount((count) => count + 1);
        }}
      />
    );
  }
  return <GuestReservations items={view.items} unread={view.unread} onLeave={leave} />;
}

function LookupForm({ onEntered }) {
  const [reservationNo, setReservationNo] = useState('');
  const [phone, setPhone] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const ready = reservationNo.length === RESERVATION_NO_LENGTH && /^\d{10,11}$/.test(phone);

  async function submit(event) {
    event.preventDefault();
    if (!ready || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await guestLookup({ reservationNo, phone });
      onEntered();
    } catch (lookupError) {
      const code = lookupError instanceof ApiError ? lookupError.code : null;
      if (code === 'unauthorized') setError('예약번호 또는 전화번호가 맞지 않아요. 다시 확인해 주세요.');
      else if (code === 'invalid') setError('예약번호 8자와 전화번호(숫자 10 ~ 11자)를 확인해 주세요.');
      else setError('확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      setSubmitting(false);
    }
  }

  return (
    <main className={styles.page}>
      <form className={styles.card} onSubmit={submit} aria-labelledby="guest-lookup-title">
        <h1 id="guest-lookup-title" className={styles.empty}>
          비회원 예약 조회
        </h1>
        <p className={styles.cap}>예약할 때 받은 예약번호와 예약한 전화번호를 넣어 주세요. 그 전화번호로 한 비회원 예약을 모두 볼 수 있어요.</p>
        <label className={formStyles.field}>
          <span className={formStyles.cap}>예약번호 · 8자</span>
          <input
            className={formStyles.input}
            value={reservationNo}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder="예: K7Q2M9XA"
            onChange={(event) => setReservationNo(event.target.value.replace(/[^0-9a-z]/gi, '').toUpperCase().slice(0, RESERVATION_NO_LENGTH))}
          />
        </label>
        <label className={formStyles.field}>
          <span className={formStyles.cap}>전화번호 · 숫자만</span>
          <input
            className={formStyles.input}
            value={phone}
            inputMode="numeric"
            autoComplete="tel"
            placeholder="01012345678"
            onChange={(event) => setPhone(event.target.value.replace(/\D/g, '').slice(0, 11))}
          />
        </label>
        {error && (
          <div className={formStyles.error} role="alert">
            {error}
          </div>
        )}
        <button type="submit" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.lookupSubmit}`} disabled={!ready || submitting}>
          {submitting ? '확인하는 중…' : '예약 확인하기'}
        </button>
        <p className={styles.cap}>
          회원이면 <Link to="/login">로그인</Link>해서 내 예약을 보세요.
        </p>
      </form>
    </main>
  );
}

function GuestReservations({ items, unread, onLeave }) {
  const [nowMs] = useState(() => Date.now());
  const upcoming = items.filter((r) => isUpcoming(r, nowMs)).reverse(); // 서버는 늦은 것부터 → 예정은 가까운 것부터
  const past = items.filter((r) => !isUpcoming(r, nowMs));

  return (
    <main className={styles.page}>
      <section className={`${styles.card} ${styles.guestHead}`}>
        <p className={styles.cap}>비회원 예약 조회 중 · 이 전화번호로 한 비회원 예약만 보여요.</p>
        <div className={styles.guestHeadActions}>
          <Link to="/guest-reservation/notifications" className={styles.actionButton}>
            알림 확인{unread > 0 ? ` · 안 읽음 ${unread}` : ''}
          </Link>
          <button type="button" className={styles.actionButton} onClick={onLeave}>
            조회 끝내기
          </button>
        </div>
      </section>

      <h2 className={styles.guestSection}>예정 {upcoming.length}</h2>
      {upcoming.length === 0 && <p className={styles.cap}>예정된 예약이 없어요.</p>}
      {upcoming.map((reservation) => (
        <GuestReservationCard key={reservation.id} reservation={reservation} upcoming />
      ))}

      <h2 className={styles.guestSection}>지난 {past.length}</h2>
      {past.length === 0 && <p className={styles.cap}>지난 예약이 없어요.</p>}
      {past.map((reservation) => (
        <GuestReservationCard key={reservation.id} reservation={reservation} />
      ))}
    </main>
  );
}

function statusText(reservation) {
  if (isChangePending(reservation)) return '변경 승인 대기';
  if (reservation.status !== 'canceled') return RESERVATION_STATUS_LABELS[reservation.status] ?? reservation.status;
  const reason = CANCEL_REASON_LABELS[reservation.cancelReason];
  return `취소${reason ? ` · ${reason}` : ''}`;
}

/** 예약 한 건(G-04와 같은 요소): 날짜 · 시각 · 가게 · 업종 · 인원 · 상태 · 예약번호, 변경 대기 표시, 변경 · 취소 */
function GuestReservationCard({ reservation, upcoming = false }) {
  const store = MOCK_STORES.find((item) => item.id === reservation.storeId);
  const badgeClass = [styles.status, reservation.status === 'canceled' && styles.statusCanceled, reservation.status === 'visited' && styles.statusVisited]
    .filter(Boolean)
    .join(' ');
  return (
    <article className={`${styles.card} ${styles.guestCard}`}>
      <div className={styles.cap}>
        {formatDateKey(serviceDateKey(Date.parse(reservation.startAt)))} · 예약번호 <b className={styles.num}>{reservation.reservationNo}</b>
      </div>
      <div className={styles.row}>
        <div className={`${styles.time} ${styles.num}`}>{formatSlotTime(reservation.startAt)}</div>
        <div className={styles.main}>
          <div className={styles.storeName}>{store?.name ?? '가게'}</div>
          <div className={styles.meta}>
            <span className={styles.num}>
              {store ? `${CATEGORY_LABELS[store.category] ?? store.category} · ` : ''}
              {reservation.partySize}명
            </span>
            <span className={badgeClass}>{statusText(reservation)}</span>
          </div>
        </div>
      </div>
      {isChangePending(reservation) && (
        <p className={styles.note}>
          변경 요청 중 · 원래 {formatSlotTime(reservation.startAt)} {reservation.partySize}명 → 바꾼{' '}
          {formatDateKey(serviceDateKey(Date.parse(reservation.pendingStartAt ?? reservation.startAt)))} {formatSlotTime(reservation.pendingStartAt ?? reservation.startAt)}{' '}
          {reservation.pendingPartySize ?? reservation.partySize}명 · 사장님 승인을 기다려요.
        </p>
      )}
      {upcoming && (
        <div className={styles.actions}>
          {/* ponytail: 비회원 변경 · 취소 API 경로가 계약(C13 · D6 · D8)에 아직 없어 버튼만 둔다. 경로가 생기면 G-04처럼 붙인다 */}
          <p className={styles.cap}>비회원 예약의 변경 · 취소는 준비 중이에요.</p>
          <div className={styles.actionButtons}>
            <button type="button" className={styles.actionButton} disabled>
              변경
            </button>
            <button type="button" className={styles.actionButton} disabled>
              취소
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
