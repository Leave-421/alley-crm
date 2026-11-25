import { Link } from 'react-router';
import { ROLE_HOME } from '@/auth/roles.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import DemoClockPanel from './DemoClockPanel.jsx';
import DemoRoleSwitch from './DemoRoleSwitch.jsx';
import styles from './DemoTools.module.css';

/**
 * 시연 도구(B20). 서비스 화면이 아니라 시연용 영역이다(07-screens 「화면 한눈에」).
 * 역할 전환(손님 회원 · 비회원 · 사장님 · 상인회)과 서버 시각 조작(D18)을 한곳에 둔다.
 */
export default function DemoToolsPage() {
  const session = useSession();
  const loggedIn = session.status === SESSION_STATUS.AUTHENTICATED;
  const backTo = loggedIn ? (ROLE_HOME[session.role] ?? '/') : '/';

  return (
    <main className={styles.page}>
      <header className={styles.toolbar}>
        <span className={styles.toolBadge}>시연 도구</span>
        <p className={styles.toolNote}>서비스 화면이 아니에요. 발표 · 시연에서 역할과 서버 시각을 바꿀 때만 써요.</p>
      </header>

      <p className={styles.current} role="status">
        {session.status === SESSION_STATUS.LOADING && '로그인 상태를 확인하고 있어요.'}
        {session.status === SESSION_STATUS.ANONYMOUS && '지금: 로그인 안 함 (비회원으로 둘러보기)'}
        {loggedIn && `지금: ${session.account?.loginId ?? ''} (${roleLabel(session.role)})`}
      </p>

      <DemoRoleSwitch />
      <DemoClockPanel />

      <Link to={backTo} className={styles.back}>
        서비스 화면으로 돌아가기
      </Link>
    </main>
  );
}

function roleLabel(role) {
  return { customer: '손님', owner: '사장님', association: '상인회' }[role] ?? role;
}
