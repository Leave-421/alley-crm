import { useState } from 'react';
import { ApiError } from '@/api/client.js';
import { CANCEL_REASON_LABELS, confirmReservation, rejectReservation, RESERVATION_STATUS_LABELS } from '@/api/ownerReservations.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { formatTime, formatWon } from './reservationFormat.js';
import styles from './OwnerReservations.module.css';

/**
 * 날짜별 목록의 예약 한 건(계약 D4 items[]).
 * 손님은 가린 이름만, 추천은 '추천으로 왔어요' 여부만 보여 준다. 보낸 가게 이름 · 전화번호는 응답에도 없다(FR-CUS-05, FR-RSV-04).
 * '신청' 예약은 승인 · 거절할 수 있다(B7, FR-RSV-03).
 */
export default function ReservationCard({ reservation, onDecided, onOutdated }) {
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

      {status === 'requested' && <DecisionActions reservation={reservation} onDecided={onDecided} onOutdated={onOutdated} />}
    </article>
  );
}

/** 신청 예약의 승인 · 거절. 거절은 한 번 더 확인한다(취소 · 환불로 되돌릴 수 없음). */
function DecisionActions({ reservation, onDecided, onOutdated }) {
  const [confirmingReject, setConfirmingReject] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const who = reservation.maskedName ?? '손님';
  const refund = reservation.depositAmount > 0 ? `예약금 ${formatWon(reservation.depositAmount)}은 100% 환불돼요` : '예약금은 없어요';

  async function decide(action) {
    setBusy(true);
    setError(null);
    try {
      if (action === 'confirm') {
        const result = await confirmReservation(reservation.id);
        onDecided(result, `${who}님 예약을 승인했어요 · 손님에게 확정 알림이 가요.`);
      } else {
        const result = await rejectReservation(reservation.id);
        onDecided(result, `${who}님 예약을 거절했어요 · 사유: 가게 거절 · ${refund}. 손님에게 알림이 가요.`);
      }
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onOutdated(`${who}님 예약은 이미 처리됐거나 예약 시각이 지나 자동 거절 대상이에요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '처리하지 못했어요.');
      setBusy(false);
    }
  }

  if (confirmingReject) {
    return (
      <div className={styles.decision}>
        <p className={styles.warning}>거절하면 예약이 취소(사유 ‘가게 거절’)되고 {refund}. 되돌릴 수 없어요.</p>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => setConfirmingReject(false)} disabled={busy}>
            돌아가기
          </Button>
          <Button variant="danger" onClick={() => decide('reject')} disabled={busy}>
            거절하기
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.decision}>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <Button variant="secondary" onClick={() => setConfirmingReject(true)} disabled={busy}>
          거절
        </Button>
        <Button onClick={() => decide('confirm')} disabled={busy}>
          승인
        </Button>
      </div>
    </div>
  );
}
