import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import { RECOMMENDATION_BADGE_LABELS } from '@/api/recommendations.js';
import { CANCEL_REASON_LABELS, RESERVATION_STATUS_LABELS, cancelReservation, getMyReservations } from '@/api/reservations.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import { ALLEY_LABELS, CATEGORY_LABELS, MOCK_STORES } from '@/map/storeCatalog.js';
import { loadRecommendationCards, nextCourseLink } from '../reserve/recommendationCards.js';
import { formatDateKey, formatSlotTime, serviceDateKey } from '../reserve/reserveTime.js';
import styles from './MyReservationsPage.module.css';

const TABS = Object.freeze([
  { key: 'upcoming', label: '예정' },
  { key: 'past', label: '지난' },
]);

const isActive = (reservation) => reservation.status === 'requested' || reservation.status === 'confirmed';
/** 앞으로 갈 예약: 신청 · 확정이고 시작 시각이 지나지 않음 */
const isUpcoming = (reservation, nowMs) => isActive(reservation) && Date.parse(reservation.startAt) > nowMs;

/**
 * 내 예약(G-04, 회원). 예정 · 지난으로 나누고, '다음 코스로 예약하기'로 만든 예약은 기준 가게 예약과 한 묶음(시간순 코스),
 * 따로 한 예약은 혼자 한 묶음. 상태(신청 · 확정 · 방문 완료 · 취소 + 사유), 기준 가게 예약이 취소 · 거절되면 '취소됨'과
 * '다음 코스는 그대로' 안내. 예약마다 그 가게 다음에 많이 찾은 곳(지도 추천과 같은 기준).
 * 비회원은 이 화면 대신 비회원 예약 조회(G-13)를 쓴다.
 */
export default function MyReservationsPage() {
  const { status, role } = useSession();
  const isMember = status === SESSION_STATUS.AUTHENTICATED && role === 'customer';
  const [tab, setTab] = useState('upcoming');
  const [mine, setMine] = useState(null); // { data } | { error }
  const [nowMs] = useState(() => Date.now());
  const [reloadCount, setReloadCount] = useState(0);
  const reload = () => setReloadCount((count) => count + 1);

  useEffect(() => {
    if (!isMember) return undefined;
    let active = true;
    getMyReservations()
      .then((data) => active && setMine({ data }))
      .catch((error) => active && setMine({ error }));
    return () => {
      active = false;
    };
  }, [isMember, reloadCount]);

  if (status === SESSION_STATUS.LOADING) return <main className={styles.page} />;
  if (!isMember) {
    return (
      <main className={styles.page}>
        <section className={styles.card}>
          <p className={styles.empty}>{status === SESSION_STATUS.AUTHENTICATED ? '손님 계정의 예약을 보여주는 화면이에요.' : '로그인하면 내 예약을 모아 볼 수 있어요.'}</p>
          <p className={styles.cap}>
            <Link to="/login">로그인</Link> · 비회원 예약은 <Link to="/guest-reservation">비회원 예약 조회</Link>에서 확인해요.
          </p>
        </section>
      </main>
    );
  }

  const groups = mine?.data?.items ?? [];
  // 묶음에 앞으로 갈 예약이 하나라도 있으면 '예정'(기준 예약이 취소돼도 다음 코스가 남아 있으면 예정에 보인다)
  const upcoming = groups.filter((group) => group.reservations.some((r) => isUpcoming(r, nowMs)));
  const past = groups.filter((group) => !upcoming.includes(group)).reverse(); // 지난 예약은 최근 것부터
  const shown = tab === 'upcoming' ? upcoming : past;

  return (
    <main className={styles.page}>
      <div className={styles.tabs} role="tablist" aria-label="예약 구분">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            className={tab === item.key ? `${styles.tab} ${styles.tabOn}` : styles.tab}
            onClick={() => setTab(item.key)}
          >
            {item.label} <span className={styles.num}>{item.key === 'upcoming' ? upcoming.length : past.length}</span>
          </button>
        ))}
      </div>

      {!mine && <p className={styles.cap}>내 예약을 불러오는 중이에요…</p>}
      {mine?.error && (
        <p className={styles.cap} role="alert">
          {mine.error instanceof ApiError && mine.error.status === 403 ? '손님 계정의 예약만 볼 수 있어요.' : '내 예약을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.'}
        </p>
      )}
      {mine?.data && shown.length === 0 && (
        <section className={styles.card}>
          <p className={styles.empty}>{tab === 'upcoming' ? '예정된 예약이 없어요.' : '지난 예약이 없어요.'}</p>
          {tab === 'upcoming' && (
            <p className={styles.cap}>
              <Link to="/alleys">골목 고르기</Link>에서 가게를 골라 예약해 보세요.
            </p>
          )}
        </section>
      )}

      {shown.map((group) => (
        <ReservationGroup key={group.courseRootId ?? group.reservations[0].id} group={group} nowMs={nowMs} onChanged={reload} />
      ))}
    </main>
  );
}

/** 한 묶음: 날짜 머리 + (코스면 '방문 코스 N곳') + 시간순 예약 */
function ReservationGroup({ group, nowMs, onChanged }) {
  const first = group.reservations[0];
  const isCourse = Boolean(group.courseRootId);
  const root = isCourse ? group.reservations.find((r) => r.id === group.courseRootId) : null;
  const rootCanceled = root?.status === 'canceled';
  return (
    <section className={styles.group} aria-label={`${formatDateKey(serviceDateKey(Date.parse(first.startAt)))} 예약`}>
      <div className={styles.groupHead}>
        <h2 className={styles.groupDate}>{formatDateKey(serviceDateKey(Date.parse(first.startAt)))}</h2>
        {isCourse && <span className={styles.badge}>방문 코스 {group.reservations.length}곳</span>}
      </div>
      {group.reservations.map((reservation, index) => (
        <div key={reservation.id}>
          {index > 0 && <div className={styles.connector} aria-hidden="true" />}
          <ReservationCard
            reservation={reservation}
            isRoot={reservation.id === group.courseRootId}
            hasNextCourse={reservation.id === group.courseRootId && group.reservations.length > 1}
            nowMs={nowMs}
            onChanged={onChanged}
          />
          {reservation.id === group.courseRootId && rootCanceled && group.reservations.length > 1 && (
            <p className={styles.note}>{reservation.storeName} 예약이 취소돼도 다음 코스 예약은 그대로예요.</p>
          )}
        </div>
      ))}
    </section>
  );
}

const isChangePending = (reservation) => Boolean(reservation.pendingStartAt || reservation.pendingPartySize);
const won = (amount) => `${amount.toLocaleString('ko-KR')}원`;

function statusText(reservation, isRoot) {
  if (isChangePending(reservation)) return '변경 승인 대기';
  if (reservation.status !== 'canceled') return RESERVATION_STATUS_LABELS[reservation.status] ?? reservation.status;
  const reason = CANCEL_REASON_LABELS[reservation.cancelReason];
  // 코스의 기준 가게 예약은 '취소됨'으로 표시(FR-RSV-12)
  return `${isRoot ? '취소됨' : '취소'}${reason ? ` · ${reason}` : ''}`;
}

/** 예약 한 건: 시각 · 가게 · 업종 · 인원 · 상태, 그리고 그 가게 다음에 많이 찾은 곳 */
function ReservationCard({ reservation, isRoot, hasNextCourse, nowMs, onChanged }) {
  const store = MOCK_STORES.find((item) => item.id === reservation.storeId);
  const upcoming = isUpcoming(reservation, nowMs);
  const badgeClass = [styles.status, reservation.status === 'canceled' && styles.statusCanceled, reservation.status === 'visited' && styles.statusVisited]
    .filter(Boolean)
    .join(' ');
  return (
    <article className={styles.card}>
      <div className={styles.row}>
        <div className={`${styles.time} ${styles.num}`}>{formatSlotTime(reservation.startAt)}</div>
        <div className={styles.main}>
          <div className={styles.storeName}>{reservation.storeName || store?.name}</div>
          <div className={styles.meta}>
            <span className={styles.num}>
              {store ? `${CATEGORY_LABELS[store.category] ?? store.category} · ` : ''}
              {reservation.partySize}명
            </span>
            <span className={badgeClass}>{statusText(reservation, isRoot)}</span>
          </div>
          {reservation.coupon && <div className={styles.cap}>쿠폰 · 예약에 적용됨</div>}
        </div>
      </div>
      {isChangePending(reservation) && (
        <p className={styles.note}>
          변경 요청 중 · 원래 {formatSlotTime(reservation.startAt)} {reservation.partySize}명 → 바꾼{' '}
          {formatDateKey(serviceDateKey(Date.parse(reservation.pendingStartAt ?? reservation.startAt)))} {formatSlotTime(reservation.pendingStartAt ?? reservation.startAt)}{' '}
          {reservation.pendingPartySize ?? reservation.partySize}명 · 사장님 승인을 기다려요. 거절되면 원래 예약이 유지돼요.
        </p>
      )}
      {upcoming && <ReservationActions reservation={reservation} hasNextCourse={hasNextCourse} onChanged={onChanged} />}
      <NextPlaces reservation={reservation} storeName={reservation.storeName || store?.name} autoLoad={upcoming} canBookNext={upcoming} />
    </article>
  );
}

/** 지금 취소하면 받는 환불 안내(D13 cancelable · refundIfCanceled): 100% · 일부 · 예약금 없음 · 마감 뒤 불가 */
function refundText(reservation) {
  if (!reservation.cancelable) return '가게 취소 마감이 지나 취소 · 변경할 수 없어요.';
  const refund = reservation.refundIfCanceled ?? 0;
  if (!reservation.depositAmount) return '예약금이 없는 예약이라 바로 취소돼요.';
  if (refund >= reservation.depositAmount) return `지금 취소하면 예약금 ${won(refund)}을 전부 돌려받아요.`;
  if (refund > 0) return `지금 취소하면 예약금 ${won(reservation.depositAmount)} 중 ${won(refund)}만 돌려받아요(일부 환불).`;
  return `지금 취소하면 예약금 ${won(reservation.depositAmount)}은 돌려받지 못해요.`;
}

/** 앞으로 갈 예약의 변경 · 취소(G-04). 변경은 확정 · 마감 전 · 변경 대기 아님(D8), 취소는 마감 전(D6) */
function ReservationActions({ reservation, hasNextCourse, onChanged }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const changeable = reservation.status === 'confirmed' && reservation.cancelable && !isChangePending(reservation);

  async function cancel() {
    setBusy(true);
    setError(null);
    try {
      await cancelReservation(reservation.id);
      onChanged();
    } catch (cancelError) {
      setError(cancelError instanceof ApiError && cancelError.code === 'conflict' ? '가게 취소 마감이 지나 취소할 수 없어요.' : '취소하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      setBusy(false);
    }
  }

  return (
    <div className={styles.actions}>
      <p className={styles.cap}>{refundText(reservation)}</p>
      {reservation.status === 'requested' && !isChangePending(reservation) && reservation.cancelable && <p className={styles.cap}>변경은 가게가 확정한 뒤에 할 수 있어요.</p>}
      {confirming ? (
        <div className={styles.confirm} role="alertdialog" aria-label="예약 취소 확인">
          <p className={styles.confirmText}>
            {reservation.storeName} 예약을 취소할까요?{hasNextCourse ? ' 다음 코스 예약은 그대로 남아요.' : ''}
          </p>
          <div className={styles.actionButtons}>
            <button type="button" className={`${styles.actionButton} ${styles.danger}`} disabled={busy} onClick={cancel}>
              {busy ? '취소하는 중…' : '예약 취소하기'}
            </button>
            <button type="button" className={styles.actionButton} disabled={busy} onClick={() => setConfirming(false)}>
              그대로 두기
            </button>
          </div>
        </div>
      ) : (
        <div className={styles.actionButtons}>
          {changeable ? (
            <Link to={`/reservations/${encodeURIComponent(reservation.id)}/change`} className={styles.actionButton}>
              변경
            </Link>
          ) : (
            <button type="button" className={styles.actionButton} disabled>
              변경
            </button>
          )}
          <button type="button" className={styles.actionButton} disabled={!reservation.cancelable} onClick={() => setConfirming(true)}>
            취소
          </button>
        </div>
      )}
      {error && (
        <p className={styles.cap} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * 그 가게 다음에 많이 찾은 곳(FR-CRS-01 · 10). 앞으로 갈 예약은 바로 불러오고 정식 제안 ID를 붙여 '다음 코스로 예약하기'까지,
 * 지난 · 취소 예약은 눌렀을 때 지도 추천만 불러온다.
 */
function NextPlaces({ reservation, storeName, autoLoad, canBookNext }) {
  const [open, setOpen] = useState(autoLoad);
  const [cards, setCards] = useState(null); // { data } | { error }

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    loadRecommendationCards({ reservationId: reservation.id, storeId: reservation.storeId, useProposals: canBookNext })
      .then((data) => active && setCards({ data }))
      .catch((error) => active && setCards({ error }));
    return () => {
      active = false;
    };
  }, [open, reservation.id, reservation.storeId, canBookNext]);

  if (!open) {
    return (
      <button type="button" className={styles.showNext} onClick={() => setOpen(true)}>
        {storeName} 다음에 많이 찾은 곳 보기
      </button>
    );
  }

  const items = cards?.data?.items ?? [];
  return (
    <div className={styles.next}>
      <div className={styles.nextHead}>{storeName} 다음에 많이 찾은 곳</div>
      {!cards && <p className={styles.cap}>불러오는 중이에요…</p>}
      {cards?.error && <p className={styles.cap}>추천을 불러오지 못했어요.</p>}
      {cards?.data && items.length === 0 && <p className={styles.cap}>지금은 추천할 가게가 없어요.</p>}
      {items.length > 0 && (
        <ul className={styles.nextList}>
          {items.map((card) => (
            <li key={card.store.id} className={styles.nextCard}>
              <span className={styles.rank}>{card.rank}</span>
              <span className={styles.nextBody}>
                <span className={styles.nextName}>{card.store.name}</span>
                <span className={styles.cap}>
                  {CATEGORY_LABELS[card.store.category] ?? card.store.category} · {RECOMMENDATION_BADGE_LABELS[card.badge] ?? card.badge}
                  {card.couponBenefit ? ' · 예약 혜택' : ''}
                </span>
                <span className={styles.nextLinks}>
                  {canBookNext && (
                    <Link {...nextCourseLink(reservation, card)} className={styles.nextBook}>
                      다음 코스로 예약하기
                    </Link>
                  )}
                  <Link to={`/alleys/${card.store.alley}`} className={styles.nextAlley}>
                    {ALLEY_LABELS[card.store.alley] ?? ''} 골목에서 보기
                  </Link>
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
