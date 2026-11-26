import { CANCEL_REASON_LABELS, RESERVATION_STATUS_LABELS } from '@/api/ownerReservations.js';
import Badge from '@/components/Badge/Badge.jsx';
import { formatTime, formatWon } from './reservationFormat.js';
import styles from './OwnerReservations.module.css';

/**
 * 날짜별 목록의 예약 한 건(계약 D4 items[]).
 * 손님은 가린 이름만, 추천은 '추천으로 왔어요' 여부만 보여 준다. 보낸 가게 이름 · 전화번호는 응답에도 없다(FR-CUS-05, FR-RSV-04).
 */
export default function ReservationCard({ reservation }) {
  const { startAt, maskedName, partySize, status, cancelReason, customerType, fromRecommendation, requestNote, depositAmount } =
    reservation;
  const canceled = status === 'canceled';
  const details = [`${partySize}명`, customerType === 'guest' ? '비회원' : '회원'];
  if (depositAmount > 0) details.push(`예약금 ${formatWon(depositAmount)}`);

  return (
    <article className={`${styles.card} ${canceled ? styles.cardCanceled : ''}`}>
      <div className={styles.cardHead}>
        <p className={styles.cardWho}>
          <time className={styles.cardTime} dateTime={startAt}>
            {formatTime(startAt)}
          </time>
          <span>{maskedName ?? '이름 없음'}</span>
        </p>
        <Badge tone={status}>{RESERVATION_STATUS_LABELS[status] ?? status}</Badge>
      </div>

      {canceled && cancelReason && <p className={styles.cancelReason}>취소 사유: {CANCEL_REASON_LABELS[cancelReason] ?? cancelReason}</p>}

      <p className={styles.cardMeta}>{details.join(' · ')}</p>
      {requestNote && <p className={styles.cardMeta}>요청: {requestNote}</p>}

      <p className={fromRecommendation ? styles.fromRecommendation : styles.cardMeta}>
        {fromRecommendation ? '추천으로 왔어요' : '일반 예약'}
      </p>
    </article>
  );
}
