import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { saveReservationSettings, SETTINGS_LIMITS as L } from '@/api/reservationSettings.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import styles from './ReservationSettings.module.css';

const percentFormat = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 });
const toPercentText = (rate) => percentFormat.format(rate * 100);

/** 서버 값 → 입력 칸 글자 */
function toDraft(settings) {
  return {
    slotMinutes: String(settings.slotMinutes),
    capacityPerSlot: String(settings.capacityPerSlot),
    unlimitedParty: settings.maxPartySize === null,
    maxPartySize: settings.maxPartySize === null ? '' : String(settings.maxPartySize),
    bookingWindowDays: String(settings.bookingWindowDays),
    cancelDeadlineHours: String(settings.cancelDeadlineHours),
    useRegionRate: settings.lateCancelRefundRate === null,
    refundPercent: settings.lateCancelRefundRate === null ? '' : String(Math.round(settings.lateCancelRefundRate * 1000) / 10),
    depositPerPerson: String(settings.depositPerPerson),
  };
}

/** 정수 칸: 범위 밖이거나 숫자가 아니면 undefined */
function parseInteger(text, { min, max }) {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  const value = Number(trimmed);
  return value >= min && value <= max ? value : undefined;
}

/** 입력 칸 글자 → { values, errors }. 범위는 계약 D5 표. */
function validate(draft) {
  const errors = {};
  const slotMinutes = parseInteger(draft.slotMinutes, L.slotMinutes);
  if (slotMinutes === undefined || slotMinutes % L.slotMinutes.step !== 0) errors.slotMinutes = `${L.slotMinutes.min} ~ ${L.slotMinutes.max}분, 10분 단위로 넣어 주세요.`;
  const capacityPerSlot = parseInteger(draft.capacityPerSlot, L.capacityPerSlot);
  if (capacityPerSlot === undefined) errors.capacityPerSlot = `${L.capacityPerSlot.min} ~ ${L.capacityPerSlot.max}팀으로 넣어 주세요.`;
  let maxPartySize = null;
  if (!draft.unlimitedParty) {
    maxPartySize = parseInteger(draft.maxPartySize, L.maxPartySize);
    if (maxPartySize === undefined) errors.maxPartySize = `${L.maxPartySize.min} ~ ${L.maxPartySize.max}명으로 넣거나 '제한 없음'을 골라 주세요.`;
  }
  const bookingWindowDays = parseInteger(draft.bookingWindowDays, L.bookingWindowDays);
  if (bookingWindowDays === undefined) errors.bookingWindowDays = `${L.bookingWindowDays.min} ~ ${L.bookingWindowDays.max}일로 넣어 주세요.`;
  const cancelDeadlineHours = parseInteger(draft.cancelDeadlineHours, L.cancelDeadlineHours);
  if (cancelDeadlineHours === undefined) errors.cancelDeadlineHours = `${L.cancelDeadlineHours.min} ~ ${L.cancelDeadlineHours.max}시간으로 넣어 주세요. 24시간 전보다 이르게 잡을 수 없어요.`;
  let lateCancelRefundRate = null;
  if (!draft.useRegionRate) {
    const trimmed = draft.refundPercent.trim();
    const percent = /^\d{1,3}(\.\d)?$/.test(trimmed) ? Number(trimmed) : NaN;
    if (Number.isNaN(percent) || percent > 100) errors.refundPercent = '0 ~ 100%, 소수 첫째 자리까지 넣어 주세요.';
    else lateCancelRefundRate = Math.round(percent * 10) / 1000;
  }
  const depositPerPerson = parseInteger(draft.depositPerPerson, L.depositPerPerson);
  if (depositPerPerson === undefined) errors.depositPerPerson = '0 ~ 1,000,000원으로 넣어 주세요. 0원이면 예약금이 없어요.';

  const values = { slotMinutes, capacityPerSlot, maxPartySize, bookingWindowDays, cancelDeadlineHours, lateCancelRefundRate, depositPerPerson };
  return { values, errors };
}

/**
 * O-03 예약 운영 설정 입력(FR-RSV-05 · 06 · 08). 저장하면 그 뒤 신청부터 적용된다(계약 D5).
 */
export default function ReservationSettingsForm({ settings, onSaved }) {
  const id = useId();
  const [draft, setDraft] = useState(() => toDraft(settings));
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const update = (key) => (event) => setDraft((previous) => ({ ...previous, [key]: event.target.type === 'checkbox' ? event.target.checked : event.target.value }));
  const regionRateLabel =
    settings.lateCancelRefundRate === null ? `권역 기본값 따르기(지금 ${toPercentText(settings.effectiveLateCancelRefundRate)}%)` : '권역 기본값 따르기';

  async function submit(event) {
    event.preventDefault();
    const { values, errors: nextErrors } = validate(draft);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;
    setSaving(true);
    setError(null);
    try {
      const saved = await saveReservationSettings(values);
      setDraft(toDraft(saved));
      onSaved(saved);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '저장하지 못했어요.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        예약 받는 방식
      </h2>

      <div className={styles.pair}>
        <TextField id={`${id}-slot`} label="슬롯 단위(분)" inputMode="numeric" value={draft.slotMinutes} onChange={update('slotMinutes')} hint="10분 단위, 10 ~ 240분" error={errors.slotMinutes} />
        <TextField id={`${id}-capacity`} label="한 슬롯에 받는 예약 수(팀)" inputMode="numeric" value={draft.capacityPerSlot} onChange={update('capacityPerSlot')} hint="1 ~ 50팀" error={errors.capacityPerSlot} />
      </div>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>한 팀 최대 인원</legend>
        <label className={styles.check}>
          <input type="checkbox" checked={draft.unlimitedParty} onChange={update('unlimitedParty')} />
          제한 없음
        </label>
        {!draft.unlimitedParty && (
          <TextField id={`${id}-party`} label="최대 인원(명)" inputMode="numeric" value={draft.maxPartySize} onChange={update('maxPartySize')} hint="1 ~ 100명" error={errors.maxPartySize} />
        )}
      </fieldset>

      <TextField
        id={`${id}-window`}
        label="예약 가능 기간(일)"
        inputMode="numeric"
        value={draft.bookingWindowDays}
        onChange={update('bookingWindowDays')}
        hint="오늘부터 며칠 뒤까지 예약을 받을지. 1 ~ 60일, 기본 30일"
        error={errors.bookingWindowDays}
      />

      <h2 className={styles.sectionTitle}>취소 · 환불</h2>
      <p className={styles.hint}>예약 시각 24시간 전까지 취소하면 예약금을 항상 100% 돌려줘요.</p>

      <TextField
        id={`${id}-deadline`}
        label="취소 마감(예약 시각 몇 시간 전까지)"
        inputMode="numeric"
        value={draft.cancelDeadlineHours}
        onChange={update('cancelDeadlineHours')}
        hint="0 ~ 24시간. 24시간 전보다 이르게 잡을 수 없어요. 마감 뒤에는 손님이 취소할 수 없어요."
        error={errors.cancelDeadlineHours}
      />

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>24시간 전이 지난 뒤 취소의 환불 비율</legend>
        <label className={styles.check}>
          <input type="checkbox" checked={draft.useRegionRate} onChange={update('useRegionRate')} />
          {regionRateLabel}
        </label>
        {!draft.useRegionRate && (
          <TextField id={`${id}-refund`} label="환불 비율(%)" inputMode="decimal" value={draft.refundPercent} onChange={update('refundPercent')} hint="0 ~ 100%" error={errors.refundPercent} />
        )}
      </fieldset>

      <h2 className={styles.sectionTitle}>예약금</h2>
      <TextField
        id={`${id}-deposit`}
        label="인원당 예약금(원)"
        inputMode="numeric"
        value={draft.depositPerPerson}
        onChange={update('depositPerPerson')}
        hint="0원이면 예약금 없음. 가상 잔액으로만 오가고 실제 결제는 없어요."
        error={errors.depositPerPerson}
      />

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <Button type="submit" disabled={saving} block>
        {saving ? '저장 중' : '저장'}
      </Button>
    </form>
  );
}
