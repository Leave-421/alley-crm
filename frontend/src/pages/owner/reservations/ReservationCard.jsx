import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import {
  CANCEL_REASON_LABELS,
  confirmCouponUse,
  confirmReservation,
  rejectReservation,
  RESERVATION_STATUS_LABELS,
  visitReservation,
  VISIT_AMOUNT_LIMITS,
} from '@/api/ownerReservations.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { formatTime, formatWon } from './reservationFormat.js';
import styles from './OwnerReservations.module.css';

/**
 * 날짜별 목록의 예약 한 건(계약 D4 items[]).
 * 손님은 가린 이름만, 추천은 '추천으로 왔어요' 여부만 보여 준다. 보낸 가게 이름 · 전화번호는 응답에도 없다(FR-CUS-05, FR-RSV-04).
 * '신청' 예약은 승인 · 거절(B7, FR-RSV-03), '확정' 예약은 방문 처리, '방문' 예약은 쿠폰 사용 확인(B8)을 한다.
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

      {status === 'visited' && reservation.visitAmount != null && (
        <p className={styles.cardMeta}>방문 금액 {formatWon(reservation.visitAmount)}(쿠폰 할인 전)</p>
      )}

      {status === 'requested' && <DecisionActions reservation={reservation} onDecided={onDecided} onOutdated={onOutdated} />}
      {status === 'confirmed' && <VisitAction reservation={reservation} onDecided={onDecided} onOutdated={onOutdated} />}
      {status === 'visited' && <CouponUseAction reservation={reservation} onDecided={onDecided} />}
    </article>
  );
}

/**
 * 확정 예약의 방문 처리(B8, FR-RSV-09). 방문 금액은 쿠폰 할인 전 결제 금액이고,
 * 쿠폰 할인 · 소개 수수료는 서버가 계산한다(D11). 쿠폰 사용 확인은 방문 처리와 따로 한다(FR-CPN-08).
 */
function VisitAction({ reservation, onDecided, onOutdated }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [amountText, setAmountText] = useState('');
  const [fieldError, setFieldError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const who = reservation.maskedName ?? '손님';

  async function submit(event) {
    event.preventDefault();
    const trimmed = amountText.replaceAll(',', '').trim();
    const amount = /^\d+$/.test(trimmed) ? Number(trimmed) : NaN;
    if (Number.isNaN(amount) || amount < VISIT_AMOUNT_LIMITS.min || amount > VISIT_AMOUNT_LIMITS.max) {
      setFieldError('0 ~ 10,000,000원 사이 숫자로 넣어 주세요.');
      return;
    }
    setFieldError(null);
    setBusy(true);
    setError(null);
    try {
      const result = await visitReservation(reservation.id, amount);
      const fee = reservation.fromRecommendation ? '추천으로 온 예약이라 쿠폰 할인 뒤 금액으로 소개 수수료가 계산돼요' : '일반 예약이라 소개 수수료는 없어요';
      onDecided(result, `${who}님 방문을 처리했어요 · 방문 금액 ${formatWon(amount)} · ${fee}.`);
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onOutdated(`${who}님 예약은 확정 상태가 아니라 방문 처리할 수 없어요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '방문 처리하지 못했어요.');
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div className={styles.decision}>
        <Button onClick={() => setOpen(true)}>방문 처리</Button>
      </div>
    );
  }

  return (
    <form className={styles.decision} onSubmit={submit} noValidate>
      <TextField
        id={`${id}-amount`}
        label="방문 금액(원, 쿠폰 할인 전)"
        inputMode="numeric"
        value={amountText}
        onChange={(event) => setAmountText(event.target.value)}
        hint="손님이 낸 결제 금액이에요. 쿠폰 할인은 이 금액에서 빠져요."
        error={fieldError}
      />
      <p className={styles.feeNote}>
        {reservation.fromRecommendation
          ? '추천으로 온 예약이에요. 쿠폰 할인 뒤 금액에 소개 수수료가 붙어요.'
          : '일반 예약이라 소개 수수료가 없어요.'}
      </p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <Button variant="secondary" onClick={() => setOpen(false)} disabled={busy}>
          취소
        </Button>
        <Button type="submit" disabled={busy}>
          {busy ? '처리 중' : '방문 처리하기'}
        </Button>
      </div>
    </form>
  );
}

/**
 * 방문 처리된 예약의 쿠폰 사용 확인(B8, FR-CPN-08, E14). 방문 전 예약에는 보이지 않는다(FR-CPN-08 #2).
 * 목록(D4)에 적용 쿠폰 칸이 아직 없어, 쿠폰이 없거나 이미 확인했으면 서버의 409로 알려 준다.
 */
function CouponUseAction({ reservation, onDecided }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function confirmUse() {
    setBusy(true);
    setMessage(null);
    try {
      const result = await confirmCouponUse(reservation.id);
      const discount = result.discountAmount > 0 ? `할인 ${formatWon(result.discountAmount)}` : '서비스 제공 쿠폰';
      onDecided(null, `${reservation.maskedName ?? '손님'}님 쿠폰 사용을 확인했어요 · ${discount}.`);
      setMessage('사용 확인됨');
    } catch (caught) {
      setMessage(caught instanceof ApiError && caught.code === 'conflict' ? caught.message : '쿠폰 사용을 확인하지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.decision}>
      {message && (
        <p className={styles.cardMeta} role="status">
          {message}
        </p>
      )}
      <Button variant="secondary" onClick={confirmUse} disabled={busy}>
        쿠폰 사용 확인
      </Button>
    </div>
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
