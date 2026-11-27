import { useId, useState } from 'react';
import { ApiError } from '@/api/client.js';
import { ASSOCIATION_COUPON_LIMITS as L, BENEFIT_TYPES, createAssociationCouponEvent, ELIGIBLE_STORE_SCOPES } from '@/api/couponEvents.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { alleyLabel, categoryLabel } from '@/pages/association/stores/storeFormat.js';
import { formatWon } from '@/pages/owner/settlement/settlementFormat.js';
import { endOfDay, startOfDay, todayKey } from './couponPeriod.js';
import styles from './AssociationCoupons.module.css';

const EMPTY = {
  title: '',
  benefitType: BENEFIT_TYPES.FIXED_DISCOUNT,
  discountAmount: '',
  discountPercent: '',
  maxDiscountAmount: '',
  scope: ELIGIBLE_STORE_SCOPES.ALL,
  storeIds: [],
  totalQuantity: '',
  issueStart: '',
  issueEnd: '',
  validStart: '',
  validEnd: '',
};

const toInt = (text) => (/^\d+$/.test(String(text).replaceAll(',', '').trim()) ? Number(String(text).replaceAll(',', '').trim()) : NaN);

/** 한 장 최대 할인액: 정액은 할인 금액, 정률은 최대 할인액(3.8 reservedBudget) */
function maxPerCoupon(draft) {
  return draft.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT ? toInt(draft.discountAmount) : toInt(draft.maxDiscountAmount);
}

/** 3.8 「검사」를 화면에서 먼저. 맞으면 요청 본문, 틀리면 errors */
function validate(draft) {
  const errors = {};
  const title = draft.title.trim();
  if (title.length < 1 || title.length > L.titleMax) errors.title = `행사 이름을 1 ~ ${L.titleMax}자로 넣어 주세요.`;
  const fixed = draft.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT;
  const discountAmount = toInt(draft.discountAmount);
  const discountPercent = toInt(draft.discountPercent);
  const maxDiscountAmount = toInt(draft.maxDiscountAmount);
  if (fixed && !(discountAmount >= 1)) errors.discountAmount = '할인 금액을 1원 이상으로 넣어 주세요.';
  if (!fixed && !(discountPercent >= L.percentMin && discountPercent <= L.percentMax)) errors.discountPercent = `할인율을 ${L.percentMin} ~ ${L.percentMax}%로 넣어 주세요.`;
  if (!fixed && !(maxDiscountAmount >= 1)) errors.maxDiscountAmount = '상인회 쿠폰은 한 장 최대 할인액이 꼭 있어야 해요.';
  const totalQuantity = toInt(draft.totalQuantity);
  if (!(totalQuantity >= 1)) errors.totalQuantity = '수량을 1장 이상으로 넣어 주세요.';
  if (draft.scope === ELIGIBLE_STORE_SCOPES.SELECTED && draft.storeIds.length === 0) errors.storeIds = '쓸 수 있는 가게를 한 곳 이상 골라 주세요.';
  const today = todayKey();
  if (!draft.issueStart || !draft.issueEnd) errors.issue = '받을 수 있는 기간을 넣어 주세요.';
  else if (draft.issueStart > draft.issueEnd) errors.issue = '받을 수 있는 기간의 끝이 시작보다 빨라요.';
  else if (draft.issueEnd < today) errors.issue = '받을 수 있는 기간이 이미 끝났어요.';
  if (!draft.validStart || !draft.validEnd) errors.valid = '쓸 수 있는 기간을 넣어 주세요.';
  else if (draft.validStart > draft.validEnd) errors.valid = '쓸 수 있는 기간의 끝이 시작보다 빨라요.';
  else if (draft.issueEnd && draft.issueEnd > draft.validEnd) errors.valid = '쓸 수 있는 기간은 받을 수 있는 기간이 끝날 때까지는 이어져야 해요.';
  if (Object.keys(errors).length > 0) return { errors };
  return {
    request: {
      title,
      benefitType: draft.benefitType,
      discountAmount: fixed ? discountAmount : null,
      discountPercent: fixed ? null : discountPercent,
      maxDiscountAmount: fixed ? null : maxDiscountAmount,
      eligibleStoreScope: draft.scope,
      storeIds: draft.scope === ELIGIBLE_STORE_SCOPES.SELECTED ? draft.storeIds : [],
      totalQuantity,
      issueStartAt: startOfDay(draft.issueStart),
      issueEndAt: endOfDay(draft.issueEnd),
      validStartAt: startOfDay(draft.validStart),
      validEndAt: endOfDay(draft.validEnd),
    },
  };
}

/**
 * A-10 상인회 쿠폰 행사 만들기(B27, FR-CPN-12). 저장 = 확정이고, 확정 뒤에는 고칠 수 없고 중단만 할 수 있다.
 * 기금에서 잡힐 예산(수량 × 한 장 최대 할인액)과 현재 기금 잔액을 같이 보여 주고, 모자라면 만들 수 없다고 안내한다.
 */
export default function AssociationCouponForm({ stores, fundBalance, onCreated, onCancel }) {
  const id = useId();
  const [draft, setDraft] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [confirming, setConfirming] = useState(null); // 확인 단계의 요청 본문
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState(null);

  const set = (field) => (event) => setDraft((previous) => ({ ...previous, [field]: event.target.value }));
  const toggleStore = (storeId) =>
    setDraft((previous) => ({
      ...previous,
      storeIds: previous.storeIds.includes(storeId) ? previous.storeIds.filter((item) => item !== storeId) : [...previous.storeIds, storeId],
    }));

  const quantity = toInt(draft.totalQuantity);
  const perCoupon = maxPerCoupon(draft);
  const budget = quantity >= 1 && perCoupon >= 1 ? quantity * perCoupon : null;
  const short = budget !== null && budget > fundBalance;

  function review(event) {
    event.preventDefault();
    const { errors: found, request } = validate(draft);
    setErrors(found ?? {});
    setFailure(null);
    if (found) return;
    if (short) {
      setFailure('공동 기금이 모자라 이 행사를 만들 수 없어요. 수량이나 할인액을 줄여 주세요.');
      return;
    }
    setConfirming(request);
  }

  async function create() {
    setBusy(true);
    setFailure(null);
    try {
      onCreated(await createAssociationCouponEvent(confirming));
    } catch (caught) {
      setConfirming(null);
      if (caught instanceof ApiError && caught.code === 'insufficient_balance') setFailure('공동 기금이 모자라 이 행사를 만들 수 없어요. 기금 잔액을 다시 확인해 주세요.');
      else setFailure(caught instanceof ApiError ? caught.message : '행사를 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={review} noValidate aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        새 상인회 쿠폰 행사
      </h2>
      {failure && (
        <p className={styles.failure} role="alert">
          {failure}
        </p>
      )}

      <TextField id={`${id}-name`} label="행사 이름" maxLength={L.titleMax} value={draft.title} onChange={set('title')} hint="회원 알림함에 보여요." error={errors.title} />

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>혜택</legend>
        <div className={styles.choices}>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-benefit`} checked={draft.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT} onChange={() => setDraft((p) => ({ ...p, benefitType: BENEFIT_TYPES.FIXED_DISCOUNT }))} />
            정액 할인
          </label>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-benefit`} checked={draft.benefitType === BENEFIT_TYPES.PERCENT_DISCOUNT} onChange={() => setDraft((p) => ({ ...p, benefitType: BENEFIT_TYPES.PERCENT_DISCOUNT }))} />
            정률 할인
          </label>
        </div>
        {draft.benefitType === BENEFIT_TYPES.FIXED_DISCOUNT ? (
          <TextField id={`${id}-amount`} label="할인 금액(원)" inputMode="numeric" value={draft.discountAmount} onChange={set('discountAmount')} error={errors.discountAmount} />
        ) : (
          <div className={styles.pair}>
            <TextField id={`${id}-percent`} label="할인율(%)" inputMode="numeric" value={draft.discountPercent} onChange={set('discountPercent')} hint="1 ~ 50%" error={errors.discountPercent} />
            <TextField id={`${id}-max`} label="한 장 최대 할인액(원)" inputMode="numeric" value={draft.maxDiscountAmount} onChange={set('maxDiscountAmount')} hint="상인회 쿠폰은 꼭 정해요." error={errors.maxDiscountAmount} />
          </div>
        )}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>쓸 수 있는 가게</legend>
        <div className={styles.choices}>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-scope`} checked={draft.scope === ELIGIBLE_STORE_SCOPES.ALL} onChange={() => setDraft((p) => ({ ...p, scope: ELIGIBLE_STORE_SCOPES.ALL }))} />
            모든 참여 가게
          </label>
          <label className={styles.choice}>
            <input type="radio" name={`${id}-scope`} checked={draft.scope === ELIGIBLE_STORE_SCOPES.SELECTED} onChange={() => setDraft((p) => ({ ...p, scope: ELIGIBLE_STORE_SCOPES.SELECTED }))} />
            가게 고르기
          </label>
        </div>
        {draft.scope === ELIGIBLE_STORE_SCOPES.SELECTED && (
          <>
            <p className={styles.hint}>
              상인회가 직접 골라요(자동 추천 없음). 가게별 통계(FR-ANL-05)는 가게 성과 비교 API(C19)가 생기면 이 목록에 더해요.
            </p>
            <ul className={styles.storePick}>
              {stores.map((store) => (
                <li key={store.id}>
                  <label className={styles.storeOption}>
                    <input type="checkbox" checked={draft.storeIds.includes(store.id)} onChange={() => toggleStore(store.id)} />
                    <span>
                      <strong>{store.name}</strong>
                      <span className={styles.muted}>
                        {categoryLabel(store.category)} · {alleyLabel(store.alley)}
                      </span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
            {errors.storeIds && <p className={styles.error}>{errors.storeIds}</p>}
          </>
        )}
      </fieldset>

      <TextField id={`${id}-quantity`} label="수량(장)" inputMode="numeric" value={draft.totalQuantity} onChange={set('totalQuantity')} hint="회원이 선착순으로 한 장씩 받아요." error={errors.totalQuantity} />

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>받을 수 있는 기간</legend>
        <div className={styles.pair}>
          <TextField id={`${id}-issue-start`} label="시작" type="date" value={draft.issueStart} onChange={set('issueStart')} />
          <TextField id={`${id}-issue-end`} label="끝(포함)" type="date" value={draft.issueEnd} onChange={set('issueEnd')} />
        </div>
        {errors.issue && <p className={styles.error}>{errors.issue}</p>}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>쓸 수 있는 기간(유효기간)</legend>
        <div className={styles.pair}>
          <TextField id={`${id}-valid-start`} label="시작" type="date" value={draft.validStart} onChange={set('validStart')} />
          <TextField id={`${id}-valid-end`} label="끝(포함)" type="date" value={draft.validEnd} onChange={set('validEnd')} />
        </div>
        <p className={styles.hint}>받은 쿠폰은 이 기간의 마지막 날까지 쓸 수 있어요. 예약 신청한 날 기준이에요.</p>
        {errors.valid && <p className={styles.error}>{errors.valid}</p>}
      </fieldset>

      <dl className={styles.budget} aria-label="기금 예산">
        <div>
          <dt>기금에서 잡힐 예산</dt>
          <dd>{budget === null ? '수량과 할인액을 넣으면 보여요' : `${formatWon(budget)}(${quantity}장 × ${formatWon(perCoupon)})`}</dd>
        </div>
        <div>
          <dt>현재 공동 기금 잔액</dt>
          <dd>{formatWon(fundBalance)}</dd>
        </div>
      </dl>
      {short && <p className={styles.warning}>공동 기금이 모자라 만들 수 없어요. 잡힐 예산이 잔액보다 {formatWon(budget - fundBalance)} 많아요.</p>}

      {confirming ? (
        <div className={styles.confirmBox}>
          <p className={styles.warning}>확정하면 값을 바꿀 수 없고 중단만 할 수 있어요. 확정과 함께 공동 기금에서 {formatWon(budget)}을 예산으로 잡아요.</p>
          <div className={styles.actions}>
            <Button variant="secondary" onClick={() => setConfirming(null)} disabled={busy}>
              돌아가기
            </Button>
            <Button onClick={create} disabled={busy}>
              {busy ? '확정 중' : '행사 확정'}
            </Button>
          </div>
        </div>
      ) : (
        <div className={styles.actions}>
          <Button variant="secondary" onClick={onCancel}>
            취소
          </Button>
          <Button type="submit" disabled={short}>
            확인하고 확정하기
          </Button>
        </div>
      )}
    </form>
  );
}
