import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { BENEFIT_TYPES, COUPON_LIMITS, createCouponEvent } from '@/api/couponEvents.js';
import Button from '@/components/Button/Button.jsx';
import { describeBenefit } from './couponFormat.js';
import styles from './CouponSettings.module.css';

const EMPTY_FORM = {
  benefitType: BENEFIT_TYPES.FIXED_DISCOUNT,
  discountAmount: '',
  discountPercent: '',
  maxDiscountAmount: '',
  serviceName: '',
  serviceValue: '',
  validDays: String(COUPON_LIMITS.validDaysDefault),
  conditions: '',
  totalQuantity: '',
};

const LOCK_NOTICE = '확정한 뒤에는 기간 · 혜택 · 조건 · 총수량 어떤 값도 바꿀 수 없고, 중단만 할 수 있어요.';

/** 새 쿠폰 행사 만들기. 입력 → 확인(요약 + 잠금 안내) → 확정 두 단계다(FR-CPN-01 #3). */
export default function CouponEventForm({ benefitTypes, onCreated, onOutdated }) {
  const id = useId();
  const [form, setForm] = useState(EMPTY_FORM);
  const [errors, setErrors] = useState({});
  const [step, setStep] = useState('edit'); // 'edit' | 'confirm'
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState(null);

  function update(field, value) {
    setForm((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  }

  function goToConfirm(event) {
    event.preventDefault();
    const found = validate(form);
    setErrors(found);
    setServerError(null);
    if (Object.keys(found).length === 0) setStep('confirm');
  }

  async function confirm() {
    setSubmitting(true);
    setServerError(null);
    try {
      const created = await createCouponEvent(toRequest(form));
      onCreated(created);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'conflict') {
        onOutdated('이미 진행 중인 행사가 있어요. 최신 상태를 다시 불러왔어요.');
        return;
      }
      setServerError(error instanceof ApiError ? error.message : '행사를 만들지 못했어요.');
      setStep('edit');
    } finally {
      setSubmitting(false);
    }
  }

  if (step === 'confirm') {
    const preview = toRequest(form);
    return (
      <section className={styles.card} aria-labelledby={`${id}-confirm`}>
        <h2 id={`${id}-confirm`} className={styles.sectionTitle}>
          이대로 확정할까요?
        </h2>
        <dl className={styles.facts}>
          <Fact label="혜택">{describeBenefit(preview)}</Fact>
          <Fact label="쿠폰 기간">다운로드 후 {preview.validDays}일 안에 예약 신청</Fact>
          <Fact label="조건">{preview.conditions ?? '없음'}</Fact>
          <Fact label="총수량">{preview.totalQuantity.toLocaleString('ko-KR')}장</Fact>
        </dl>
        <p className={styles.warning} role="note">
          {LOCK_NOTICE}
        </p>
        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => setStep('edit')} disabled={submitting}>
            돌아가서 고치기
          </Button>
          <Button onClick={confirm} disabled={submitting}>
            {submitting ? '확정하는 중…' : '확정하기'}
          </Button>
        </div>
      </section>
    );
  }

  return (
    <form className={styles.card} onSubmit={goToConfirm} noValidate aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        새 쿠폰 행사 만들기
      </h2>

      {serverError && (
        <p className={styles.error} role="alert">
          {serverError}
        </p>
      )}

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>혜택 유형</legend>
        <div className={styles.choices}>
          {benefitTypes.map((type) => (
            <label key={type.value} className={styles.choice}>
              <input
                type="radio"
                name={`${id}-benefitType`}
                value={type.value}
                checked={form.benefitType === type.value}
                onChange={() => update('benefitType', type.value)}
              />
              <span>{type.label}</span>
            </label>
          ))}
        </div>
      </fieldset>

      {form.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT && (
        <NumberField
          id={`${id}-discountAmount`}
          label="할인 금액(원)"
          hint="방문 금액까지만 깎고, 남는 할인은 사라져요."
          value={form.discountAmount}
          error={errors.discountAmount}
          min={1}
          onChange={(value) => update('discountAmount', value)}
        />
      )}

      {form.benefitType === BENEFIT_TYPES.PERCENT_DISCOUNT && (
        <>
          <NumberField
            id={`${id}-discountPercent`}
            label={`할인율(%, ${COUPON_LIMITS.percentMin} ~ ${COUPON_LIMITS.percentMax})`}
            hint="방문 금액(할인 전)에 곱하고 1원 미만은 버려요."
            value={form.discountPercent}
            error={errors.discountPercent}
            min={COUPON_LIMITS.percentMin}
            max={COUPON_LIMITS.percentMax}
            onChange={(value) => update('discountPercent', value)}
          />
          <NumberField
            id={`${id}-maxDiscountAmount`}
            label="최대 할인액(원, 선택)"
            hint="비우면 상한이 없어요."
            value={form.maxDiscountAmount}
            error={errors.maxDiscountAmount}
            min={1}
            onChange={(value) => update('maxDiscountAmount', value)}
          />
        </>
      )}

      {form.benefitType === BENEFIT_TYPES.SERVICE && (
        <>
          <TextField
            id={`${id}-serviceName`}
            label="서비스 내용"
            value={form.serviceName}
            error={errors.serviceName}
            maxLength={COUPON_LIMITS.serviceNameMaxLength}
            placeholder="예: 음료 1잔"
            onChange={(value) => update('serviceName', value)}
          />
          <NumberField
            id={`${id}-serviceValue`}
            label="쿠폰 비용 환산 금액(원)"
            hint="쿠폰 비용 기록에만 쓰고, 수수료 계산에는 쓰지 않아요."
            value={form.serviceValue}
            error={errors.serviceValue}
            min={1}
            onChange={(value) => update('serviceValue', value)}
          />
        </>
      )}

      <NumberField
        id={`${id}-validDays`}
        label={`쿠폰 기간(일, ${COUPON_LIMITS.validDaysMin} ~ ${COUPON_LIMITS.validDaysMax})`}
        hint="손님이 쿠폰을 받은 뒤 이 기간 안에 예약을 신청하면 혜택이 적용돼요."
        value={form.validDays}
        error={errors.validDays}
        min={COUPON_LIMITS.validDaysMin}
        max={COUPON_LIMITS.validDaysMax}
        onChange={(value) => update('validDays', value)}
      />

      <div className={styles.field}>
        <label htmlFor={`${id}-conditions`} className={styles.label}>
          사용 조건(선택)
        </label>
        <textarea
          id={`${id}-conditions`}
          className={styles.input}
          rows={2}
          maxLength={COUPON_LIMITS.conditionsMaxLength}
          placeholder="예: 2인 이상 방문 시"
          value={form.conditions}
          aria-invalid={Boolean(errors.conditions)}
          aria-describedby={errors.conditions ? `${id}-conditions-error` : undefined}
          onChange={(event) => update('conditions', event.target.value)}
        />
        <p className={styles.hint}>
          {form.conditions.length} / {COUPON_LIMITS.conditionsMaxLength}자
        </p>
        {errors.conditions && (
          <p id={`${id}-conditions-error`} className={styles.error}>
            {errors.conditions}
          </p>
        )}
      </div>

      <NumberField
        id={`${id}-totalQuantity`}
        label="총수량(장)"
        hint="모두 나가면 '예약 혜택' 배지가 내려가고 새 발급이 멈춰요."
        value={form.totalQuantity}
        error={errors.totalQuantity}
        min={1}
        onChange={(value) => update('totalQuantity', value)}
      />

      <p className={styles.warning} role="note">
        {LOCK_NOTICE}
      </p>
      <Button type="submit" block>
        확인하고 확정하기
      </Button>
    </form>
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

function NumberField({ id, label, hint, value, error, min, max, onChange }) {
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        id={id}
        className={styles.input}
        type="number"
        inputMode="numeric"
        step={1}
        min={min}
        max={max}
        value={value}
        aria-invalid={Boolean(error)}
        aria-describedby={[hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {hint && (
        <p id={`${id}-hint`} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className={styles.error}>
          {error}
        </p>
      )}
    </div>
  );
}

function TextField({ id, label, value, error, maxLength, placeholder, onChange }) {
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        id={id}
        className={styles.input}
        type="text"
        maxLength={maxLength}
        placeholder={placeholder}
        value={value}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && (
        <p id={`${id}-error`} className={styles.error}>
          {error}
        </p>
      )}
    </div>
  );
}

function toInt(text) {
  const trimmed = String(text).trim();
  if (!/^\d+$/.test(trimmed)) return null;
  return Number(trimmed);
}

/** 계약 3.2 「POST /api/v1/coupon-events」 검사를 화면에서 미리 한다. 최종 판단은 서버다. */
function validate(form) {
  const errors = {};
  const atLeastOne = (field, message) => {
    const value = toInt(form[field]);
    if (value === null || value < 1) errors[field] = message;
  };

  if (form.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT) {
    atLeastOne('discountAmount', '할인 금액을 1원 이상 넣어 주세요.');
  }
  if (form.benefitType === BENEFIT_TYPES.PERCENT_DISCOUNT) {
    const percent = toInt(form.discountPercent);
    if (percent === null || percent < COUPON_LIMITS.percentMin || percent > COUPON_LIMITS.percentMax) {
      errors.discountPercent = `할인율은 ${COUPON_LIMITS.percentMin} ~ ${COUPON_LIMITS.percentMax} 사이 정수로 넣어 주세요.`;
    }
    if (form.maxDiscountAmount.trim() !== '') atLeastOne('maxDiscountAmount', '최대 할인액은 비우거나 1원 이상으로 넣어 주세요.');
  }
  if (form.benefitType === BENEFIT_TYPES.SERVICE) {
    const name = form.serviceName.trim();
    if (name.length < 1 || name.length > COUPON_LIMITS.serviceNameMaxLength) {
      errors.serviceName = `서비스 내용을 1 ~ ${COUPON_LIMITS.serviceNameMaxLength}자로 넣어 주세요.`;
    }
    atLeastOne('serviceValue', '환산 금액을 1원 이상 넣어 주세요. 환산 금액 없이는 만들 수 없어요.');
  }

  const days = toInt(form.validDays);
  if (days === null || days < COUPON_LIMITS.validDaysMin || days > COUPON_LIMITS.validDaysMax) {
    errors.validDays = `쿠폰 기간은 ${COUPON_LIMITS.validDaysMin} ~ ${COUPON_LIMITS.validDaysMax}일 사이 정수로 넣어 주세요.`;
  }
  if (form.conditions.length > COUPON_LIMITS.conditionsMaxLength) {
    errors.conditions = `사용 조건은 ${COUPON_LIMITS.conditionsMaxLength}자 이하로 넣어 주세요.`;
  }
  atLeastOne('totalQuantity', '총수량을 1장 이상 넣어 주세요.');
  return errors;
}

/** 계약 요청 모양. 고른 유형에 쓰지 않는 칸은 null로 보낸다. */
function toRequest(form) {
  const type = form.benefitType;
  const conditions = form.conditions.trim();
  return {
    benefitType: type,
    discountAmount: type === BENEFIT_TYPES.FIXED_DISCOUNT ? toInt(form.discountAmount) : null,
    discountPercent: type === BENEFIT_TYPES.PERCENT_DISCOUNT ? toInt(form.discountPercent) : null,
    maxDiscountAmount:
      type === BENEFIT_TYPES.PERCENT_DISCOUNT && form.maxDiscountAmount.trim() !== '' ? toInt(form.maxDiscountAmount) : null,
    serviceName: type === BENEFIT_TYPES.SERVICE ? form.serviceName.trim() : null,
    serviceValue: type === BENEFIT_TYPES.SERVICE ? toInt(form.serviceValue) : null,
    validDays: toInt(form.validDays),
    conditions: conditions === '' ? null : conditions,
    totalQuantity: toInt(form.totalQuantity),
  };
}
