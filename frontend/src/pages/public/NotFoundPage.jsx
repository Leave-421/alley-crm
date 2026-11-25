import { Link } from 'react-router';
import styles from '@/pages/page.module.css';

export default function NotFoundPage() {
  return (
    <main className={styles.page}>
      <section className={styles.card}>
        <h1 className={styles.title}>없는 화면이에요</h1>
        <p className={styles.muted}>주소를 다시 확인해 주세요.</p>
        <Link to="/">처음으로 가기</Link>
      </section>
    </main>
  );
}
