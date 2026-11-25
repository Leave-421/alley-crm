import styles from './TextField.module.css';

/**
 * 공통 입력 칸: 라벨 + 입력 + 도움말 + 오류. 오류는 글자와 굵은 테두리로 함께 보여 준다(NFR-11).
 * id는 부르는 쪽이 useId로 만들어 넘긴다. 나머지 props는 <input>에 그대로 넘긴다.
 */
export default function TextField({ id, label, hint, error, ...inputProps }) {
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input id={id} className={styles.input} aria-invalid={Boolean(error)} aria-describedby={describedBy} {...inputProps} />
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
