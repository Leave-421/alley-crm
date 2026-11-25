import Badge from '@/components/Badge/Badge.jsx';
import styles from './page.module.css';

/**
 * 화면 이슈가 실제 화면으로 바꾸기 전까지 경로만 잡아 두는 자리표시.
 * narrow면 넓은 화면에서도 좁게 가운데 둔다(로그인 · 가입처럼 레이아웃 밖 화면).
 */
export default function PlaceholderPage({ screenId, title, narrow = false }) {
  return (
    <main className={narrow ? `${styles.page} ${styles.narrow}` : styles.page}>
      <section className={styles.card}>
        <Badge>{screenId}</Badge>
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.muted}>이 화면은 해당 화면 이슈에서 만듭니다.</p>
      </section>
    </main>
  );
}
