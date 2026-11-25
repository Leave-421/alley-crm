import styles from './Button.module.css';

const VARIANTS = ['primary', 'secondary', 'danger'];

/**
 * 공통 버튼. 모양은 확정 목업의 .btn-p(주요) · .btn-s(보조, 테두리) · .btn-d(위험)을 따른다.
 * 기본 type은 "button"이다(폼 안에서 뜻하지 않게 제출되지 않게). 제출 버튼은 type="submit"을 준다.
 */
export default function Button({ variant = 'primary', type = 'button', block = false, className, ...props }) {
  const classes = [styles.button, styles[VARIANTS.includes(variant) ? variant : 'primary']];
  if (block) classes.push(styles.block);
  if (className) classes.push(className);
  return <button type={type} className={classes.join(' ')} {...props} />;
}
