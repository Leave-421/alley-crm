import { ROLE_HOME } from '@/auth/roles.js';

/**
 * 로그인 뒤 돌아갈 주소. `next`는 주소창에서 오므로 우리 앱 안 경로만 받는다(열린 리다이렉트 방지).
 * - `/`로 시작하고 `//`(다른 사이트)나 `/\`로 시작하지 않는 경로만 허용
 * - 로그인 · 가입 화면으로 다시 가지 않음
 * 받을 수 없으면 역할의 첫 화면(ROLE_HOME).
 */
export function afterLoginPath(next, role) {
  const fallback = ROLE_HOME[role] ?? '/';
  if (typeof next !== 'string' || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return fallback;
  }
  if (next.startsWith('/login') || next.startsWith('/signup')) return fallback;
  return next;
}
