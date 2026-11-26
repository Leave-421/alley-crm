import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { approveStoreJoin, rejectStoreJoin } from '@/api/stores.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { alleyLabel, categoryLabel, formatHours } from './storeFormat.js';
import styles from './StoreManagement.module.css';

/**
 * 가입 신청 목록과 승인 · 거절(FR-STO-01). 오래된 신청부터(계약 C5).
 * 처리하면 사장님에게 결과 알림이 간다(서버 C14). 거절한 가게는 사장님이 고쳐서 다시 신청할 수 있다.
 */
export default function StoreJoinRequests({ joins, totalElements, onDecided, onOutdated }) {
  const id = useId();
  return (
    <section className={styles.section} aria-labelledby={`${id}-title`}>
      <div className={styles.sectionHead}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          가입 신청
        </h2>
        <span className={styles.count}>{totalElements}건</span>
      </div>
      {joins.length === 0 ? (
        <p className={styles.empty}>기다리는 가입 신청이 없어요.</p>
      ) : (
        <ul className={styles.cards}>
          {joins.map((join) => (
            <li key={join.storeId}>
              <JoinCard join={join} onDecided={onDecided} onOutdated={onOutdated} />
            </li>
          ))}
        </ul>
      )}
      {totalElements > joins.length && <p className={styles.hint}>오래된 신청 {joins.length}건만 보여요. 처리하면 다음 신청이 이어서 보여요.</p>}
    </section>
  );
}

function JoinCard({ join, onDecided, onOutdated }) {
  const [confirmingReject, setConfirmingReject] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function decide(action) {
    setBusy(true);
    setError(null);
    try {
      const result = action === 'approve' ? await approveStoreJoin(join.storeId) : await rejectStoreJoin(join.storeId);
      onDecided(result);
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onOutdated(`‘${join.name}’ 신청은 이미 처리됐거나 바뀌었어요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '처리하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <h3 className={styles.cardTitle}>{join.name}</h3>
        <Badge tone="requested">승인 대기</Badge>
      </div>
      <p className={styles.meta}>
        {categoryLabel(join.category)} · {alleyLabel(join.alley)}
      </p>
      <p className={styles.meta}>{formatHours(join)}</p>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {confirmingReject ? (
        <div className={styles.confirmBox}>
          <p className={styles.warning}>거절하면 사장님에게 결과 알림이 가요. 사장님은 가게 정보를 고쳐 다시 신청할 수 있어요.</p>
          <div className={styles.actions}>
            <Button variant="secondary" onClick={() => setConfirmingReject(false)} disabled={busy}>
              돌아가기
            </Button>
            <Button variant="danger" onClick={() => decide('reject')} disabled={busy}>
              거절하기
            </Button>
          </div>
        </div>
      ) : (
        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => setConfirmingReject(true)} disabled={busy}>
            거절
          </Button>
          <Button onClick={() => decide('approve')} disabled={busy}>
            승인
          </Button>
        </div>
      )}
    </article>
  );
}
