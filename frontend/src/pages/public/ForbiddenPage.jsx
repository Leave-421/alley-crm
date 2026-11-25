import { Link } from 'react-router';
import { ROLE_HOME } from '@/auth/roles.js';
import { useSession } from '@/auth/session.js';
import styles from '@/pages/page.module.css';

/** 로그인은 했지만 내 역할로 열 수 없는 화면에 들어왔을 때. */
export default function ForbiddenPage() {
  const { role } = useSession();
  const home = ROLE_HOME[role] ?? '/';
  return (
    <main className={`${styles.page} ${styles.narrow}`}>
      <section className={styles.card}>
        <h1 className={styles.title}>이 화면은 볼 수 없어요</h1>
        <p className={styles.muted}>지금 로그인한 계정으로는 들어갈 수 없는 화면입니다.</p>
        <Link to={home}>내 첫 화면으로 가기</Link>
      </section>
    </main>
  );
}
