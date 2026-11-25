import { createContext, useContext } from 'react';

export const SESSION_STATUS = Object.freeze({
  LOADING: 'loading', // 서버에 로그인 상태를 묻는 중
  ANONYMOUS: 'anonymous', // 로그인 안 함
  AUTHENTICATED: 'authenticated', // 로그인함, role이 채워짐
});

export const ANONYMOUS_SESSION = Object.freeze({ status: SESSION_STATUS.ANONYMOUS, role: null, account: null });
export const LOADING_SESSION = Object.freeze({ status: SESSION_STATUS.LOADING, role: null, account: null });

/** 계정 응답(Account, CONTRACTS_API.md C2)으로 로그인 세션을 만든다. */
export function sessionFromAccount(account) {
  return { status: SESSION_STATUS.AUTHENTICATED, role: account.role, account };
}

export const SessionContext = createContext(null);

/** 현재 세션 { status, role, account }와 세션을 바꾸는 setSession을 돌려준다. */
export function useSession() {
  const value = useContext(SessionContext);
  if (value === null) {
    throw new Error('useSession은 SessionProvider 안에서만 쓸 수 있다');
  }
  return value;
}
