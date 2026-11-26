import { STORE_ALLEY_LABELS, STORE_CATEGORY_LABELS, STORE_JOIN_RULES, WEEKDAY_LABELS } from '@/api/stores.js';
import TextField from '@/components/TextField/TextField.jsx';
import styles from './StoreJoin.module.css';

/** O-11 가게 정보 입력 칸: 가게 이름 · 업종 · 골목 · 영업시간 · 휴무일(FR-STO-01). 신청과 다시 신청이 같이 쓴다. */
export default function StoreInfoFields({ id, info, errors, onChange }) {
  const set = (field) => (event) => onChange({ ...info, [field]: event.target.value });
  const toggleDay = (day) =>
    onChange({ ...info, closedDays: info.closedDays.includes(day) ? info.closedDays.filter((item) => item !== day) : [...info.closedDays, day] });

  return (
    <>
      <TextField
        id={`${id}-name`}
        label="가게 이름"
        maxLength={STORE_JOIN_RULES.nameMax}
        value={info.name}
        error={errors.name}
        onChange={set('name')}
      />
      <div className={styles.pair}>
        <SelectField id={`${id}-category`} label="업종" value={info.category} error={errors.category} onChange={set('category')} options={STORE_CATEGORY_LABELS} />
        <SelectField id={`${id}-alley`} label="골목" value={info.alley} error={errors.alley} onChange={set('alley')} options={STORE_ALLEY_LABELS} hint="지도 위치와 주소는 승인 뒤 상인회가 관리해요." />
      </div>
      <div className={styles.pair}>
        <TextField id={`${id}-open`} label="영업 시작" type="time" value={info.openTime} error={errors.openTime} onChange={set('openTime')} />
        <TextField id={`${id}-close`} label="영업 종료" type="time" value={info.closeTime} error={errors.closeTime} onChange={set('closeTime')} />
      </div>
      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>휴무일</legend>
        <div className={styles.days}>
          {Object.entries(WEEKDAY_LABELS).map(([day, label]) => (
            <label key={day} className={styles.day}>
              <input type="checkbox" checked={info.closedDays.includes(day)} onChange={() => toggleDay(day)} />
              {label}
            </label>
          ))}
        </div>
        <p className={styles.hint}>{info.closedDays.length === 0 ? '고르지 않으면 연중무휴예요.' : '고른 요일에는 예약을 받지 않아요.'}</p>
      </fieldset>
    </>
  );
}

function SelectField({ id, label, value, error, onChange, options, hint }) {
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <select id={id} className={styles.select} value={value} onChange={onChange} aria-invalid={Boolean(error)} aria-describedby={describedBy}>
        <option value="">고르기</option>
        {Object.entries(options).map(([optionValue, optionLabel]) => (
          <option key={optionValue} value={optionValue}>
            {optionLabel}
          </option>
        ))}
      </select>
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
