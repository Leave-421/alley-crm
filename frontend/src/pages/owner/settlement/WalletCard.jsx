import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { CHARGE_LIMITS, chargeMyWallet } from '@/api/settlement.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { formatWon } from './settlementFormat.js';
import styles from './Settlement.module.css';

/**
 * 가게 가상 잔액과 가상 충전(FR-STL-06, 기획 11-25 (4)). 실제 결제는 없다(NFR-02).
 * 잔액이 0보다 적으면(needsCharge) '충전 필요' 안내 — 추천 · 예약 받기는 그대로다(FR-STL-06 #3).
 */
export default function WalletCard({ wallet, onCharged }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [amountText, setAmountText] = useState('');
  const [fieldError, setFieldError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function submit(event) {
    event.preventDefault();
    const trimmed = amountText.replaceAll(',', '').trim();
    const amount = /^\d+$/.test(trimmed) ? Number(trimmed) : NaN;
    if (Number.isNaN(amount) || amount < CHARGE_LIMITS.min || amount > CHARGE_LIMITS.max) {
      setFieldError('1,000 ~ 1,000,000원 사이로 넣어 주세요.');
      return;
    }
    setFieldError(null);
    setBusy(true);
    setError(null);
    try {
      const next = await chargeMyWallet(amount);
      setOpen(false);
      setAmountText('');
      onCharged(next, amount);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '충전하지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.card} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        가게 가상 잔액
      </h2>
      <p className={wallet.balance < 0 ? `${styles.balance} ${styles.negative}` : styles.balance}>{formatWon(wallet.balance)}</p>
      {wallet.needsCharge && (
        <p className={styles.warning} role="status">
          충전이 필요해요. 잔액이 0원보다 적어요. 추천과 예약 받기는 그대로 계속돼요.
        </p>
      )}
      <p className={styles.hint}>소개 수수료는 방문 처리 때 이 잔액에서 공동 기금으로 빠져나가요. 실제 돈은 오가지 않아요.</p>

      {open ? (
        <form className={styles.form} onSubmit={submit} noValidate>
          <TextField
            id={`${id}-amount`}
            label="충전 금액(원)"
            inputMode="numeric"
            value={amountText}
            onChange={(event) => setAmountText(event.target.value)}
            hint="1,000 ~ 1,000,000원. 가상 금액이에요."
            error={fieldError}
          />
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
              {busy ? '충전 중' : '충전하기'}
            </Button>
          </div>
        </form>
      ) : (
        <Button variant={wallet.needsCharge ? 'primary' : 'secondary'} onClick={() => setOpen(true)}>
          가상 충전
        </Button>
      )}
    </section>
  );
}
