import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { stopCouponEvent } from '@/api/couponEvents.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { describeBenefit } from './couponFormat.js';
import styles from './CouponSettings.module.css';

const STOP_WARNING = '중단하면 다시 켜거나 고칠 수 없어요. 새 발급만 멈추고, 이미 받은 쿠폰은 자기 기한까지 그대로 쓸 수 있어요.';

/** 진행 중인 행사. 확정된 값은 읽기만 하고, 중단만 할 수 있다(FR-CPN-01 · 11). */
export default function CouponEventSummary({ event, onStopped, onOutdated }) {
  const id = useId();
  const [confirmingStop, setConfirmingStop] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState(null);
  const soldOut = event.remainingQuantity <= 0;

  async function stop() {
    setStopping(true);
    setError(null);
    try {
      await stopCouponEvent(event.id);
      onStopped();
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onOutdated('이 행사는 이미 중단됐거나 바뀌었어요. 최신 상태를 다시 불러왔어요.');
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '행사를 중단하지 못했어요.');
    } finally {
      setStopping(false);
    }
  }

  return (
    <section className={styles.card} aria-labelledby={`${id}-title`}>
      <div className={styles.cardHead}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          진행 중인 쿠폰 행사
        </h2>
        {event.benefitAvailable ? <Badge tone="confirmed">예약 혜택 표시 중</Badge> : <Badge>예약 혜택 내려감</Badge>}
      </div>

      {soldOut && (
        <p className={styles.warning} role="status">
          총수량이 모두 나갔어요. &lsquo;예약 혜택&rsquo; 배지가 내려가고 새 발급이 멈췄어요.
        </p>
      )}

      <dl className={styles.facts}>
        <Fact label="혜택">{describeBenefit(event)}</Fact>
        <Fact label="쿠폰 기간">다운로드 후 {event.validDays}일 안에 예약 신청</Fact>
        <Fact label="조건">{event.conditions ?? '없음'}</Fact>
        <Fact label="총수량">
          {event.totalQuantity.toLocaleString('ko-KR')}장 · 발급 {event.issuedCount.toLocaleString('ko-KR')}장 · 남음{' '}
          {event.remainingQuantity.toLocaleString('ko-KR')}장
        </Fact>
        <Fact label="확정">{formatDateTime(event.confirmedAt)}</Fact>
      </dl>

      <p className={styles.hint}>확정한 행사라 값을 바꿀 수 없어요. 조건을 바꾸려면 중단한 뒤 새 행사를 만들어 주세요.</p>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {confirmingStop ? (
        <div className={styles.confirmBox} role="alertdialog" aria-labelledby={`${id}-stop`} aria-describedby={`${id}-stop-desc`}>
          <p id={`${id}-stop`} className={styles.label}>
            이 행사를 중단할까요?
          </p>
          <p id={`${id}-stop-desc`} className={styles.warning}>
            {STOP_WARNING}
          </p>
          <div className={styles.actions}>
            <Button variant="secondary" onClick={() => setConfirmingStop(false)} disabled={stopping}>
              그대로 두기
            </Button>
            <Button variant="danger" onClick={stop} disabled={stopping}>
              {stopping ? '중단하는 중…' : '중단하기'}
            </Button>
          </div>
        </div>
      ) : (
        <Button variant="danger" block onClick={() => setConfirmingStop(true)}>
          행사 중단
        </Button>
      )}
    </section>
  );
}

function Fact({ label, children }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function formatDateTime(iso) {
  if (!iso) return '-';
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}
