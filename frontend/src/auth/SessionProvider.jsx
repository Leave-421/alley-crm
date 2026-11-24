import { useMemo, useState } from 'react';
import { ANONYMOUS_SESSION, SessionContext } from '@/auth/session.js';

/**
 * 로그인 상태를 앱 전체에 나눠 준다.
 * B1에서는 '로그인 안 함'으로 시작한다. 서버에서 현재 로그인 정보를 읽어 오는 일은
 * C2(#24)의 계약이 나온 뒤 B4(로그인)에서, 시연용 역할 전환은 B20에서 setSession으로 붙인다.
 */
export default function SessionProvider({ children, initialSession = ANONYMOUS_SESSION }) {
  const [session, setSession] = useState(initialSession);
  const value = useMemo(() => ({ ...session, setSession }), [session]);
  return <SessionContext value={value}>{children}</SessionContext>;
}
