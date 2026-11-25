import { useId, useState } from 'react';
import { useNavigate } from 'react-router';
import { login, logout } from '@/api/auth.js';
import { ApiError } from '@/api/client.js';
import { ROLE_HOME } from '@/auth/roles.js';
import { ANONYMOUS_SESSION, SESSION_STATUS, sessionFromAccount, useSession } from '@/auth/session.js';
import { DEMO_PASSWORD, DEMO_ROLES } from '@/demo/demoAccounts.js';
import styles from './DemoTools.module.css';

const IS_MOCK = import.meta.env.MODE === 'mock';

/** 시연 계정으로 로그인해 역할을 바꾼다. 로그인은 C2의 일반 로그인 API를 그대로 쓴다. */
export default function DemoRoleSwitch() {
  const id = useId();
  const session = useSession();
  const navigate = useNavigate();
  const [busyKey, setBusyKey] = useState(null);
  const [error, setError] = useState(null);

  const currentKey =
    session.status === SESSION_STATUS.ANONYMOUS
      ? 'guest'
      : DEMO_ROLES.find((item) => item.loginId && item.loginId === session.account?.loginId)?.key;

  async function switchTo(item) {
    setBusyKey(item.key);
    setError(null);
    try {
      if (session.status === SESSION_STATUS.AUTHENTICATED) {
        await logout().catch((caught) => {
          // 서버 세션이 이미 끝났으면(401) 그대로 진행한다
          if (!(caught instanceof ApiError && caught.status === 401)) throw caught;
        });
      }
      if (!item.loginId) {
        session.setSession(ANONYMOUS_SESSION);
        navigate('/');
        return;
      }
      if (!DEMO_PASSWORD && !IS_MOCK) {
        throw new Error('시연 계정 비밀번호가 없어요. frontend/.env.development.local에 VITE_DEMO_PASSWORD를 넣어 주세요.');
      }
      const account = await login(item.loginId, DEMO_PASSWORD || 'mock-only-password');
      session.setSession(sessionFromAccount(account));
      navigate(ROLE_HOME[account.role] ?? '/');
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        setError(`시연 계정 '${item.loginId}'로 로그인하지 못했어요. 시드에 시연 계정이 있는지, 비밀번호가 맞는지 확인해 주세요.`);
      } else {
        setError(caught?.message ?? '역할을 바꾸지 못했어요.');
      }
      session.setSession(ANONYMOUS_SESSION);
    } finally {
      setBusyKey(null);
    }
  }

  return (
    <section className={styles.card} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        역할 전환
      </h2>
      <p className={styles.hint}>같은 데이터로 세 가지 화면을 오갈 수 있어요. 고르면 그 역할의 첫 화면으로 가요.</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <ul className={styles.roles}>
        {DEMO_ROLES.map((item) => {
          const current = item.key === currentKey;
          return (
            <li key={item.key}>
              <button
                type="button"
                className={current ? `${styles.role} ${styles.roleCurrent}` : styles.role}
                aria-pressed={current}
                disabled={busyKey !== null}
                onClick={() => switchTo(item)}
              >
                <span className={styles.roleLabel}>
                  {item.label}
                  {current && <span className={styles.currentMark}>지금</span>}
                </span>
                <span className={styles.roleDesc}>{busyKey === item.key ? '바꾸는 중…' : item.description}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
