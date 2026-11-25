import styles from './Badge.module.css';

const TONES = ['neutral', 'requested', 'confirmed', 'visited', 'canceled'];

/**
 * 상태 배지. 색만으로 상태를 나누지 않도록(NFR-11) 항상 글자를 넣고, tone마다 테두리 모양도 다르게 한다.
 * 예약 상태 글자(신청 · 확정 · 방문 · 취소)와 값 대응은 예약 화면 이슈에서 정한다(계약 「3. Entity · 상태」).
 */
export default function Badge({ tone = 'neutral', children }) {
  const toneClass = TONES.includes(tone) ? styles[tone] : styles.neutral;
  return <span className={`${styles.badge} ${toneClass}`}>{children}</span>;
}
