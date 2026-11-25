import { useEffect, useMemo, useState } from 'react';
import { getMe } from '@/api/auth.js';
import { ApiError, onUnauthorized } from '@/api/client.js';
import { ANONYMOUS_SESSION, LOADING_SESSION, SESSION_STATUS, SessionContext, sessionFromAccount } from '@/auth/session.js';

/**
 * 로그인 상태를 앱 전체에 나눠 준다.
 * - 앱을 열면 `GET /api/v1/auth/me`(C2)로 서버 세션을 읽어 복구한다. 새로고침해도 로그인이 유지된다.
 * - API가 401을 돌려주면 '로그인 안 함'으로 바꾼다. 그러면 역할 가드가 로그인 화면으로 보낸다.
 * - 로그인(B4) · 시연 역할 전환(B20)은 setSession으로 바꾼다.
 */
export default function SessionProvider({ children, initialSession = LOADING_SESSION }) {
  const [session, setSession] = useState(initialSession);

  useEffect(() => onUnauthorized(() => setSession(ANONYMOUS_SESSION)), []);

  useEffect(() => {
    if (initialSession.status !== SESSION_STATUS.LOADING) return undefined;
    let active = true;
    getMe()
      .then((account) => {
        if (active) setSession(sessionFromAccount(account));
      })
      .catch((error) => {
        if (!(error instanceof ApiError) || error.status !== 401) {
          console.warn('로그인 상태를 확인하지 못해 로그인 안 함으로 시작해요.', error);
        }
        if (active) setSession(ANONYMOUS_SESSION);
      });
    return () => {
      active = false;
    };
  }, [initialSession.status]);

  const value = useMemo(() => ({ ...session, setSession }), [session]);
  return <SessionContext value={value}>{children}</SessionContext>;
}
