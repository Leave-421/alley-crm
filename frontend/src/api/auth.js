import { api } from '@/api/client.js';

// 계정 API — CONTRACTS_API.md 「1. 계정」(C2). 응답 Account: { id, loginId, role, storeId, regionId }

/** 로그인. 성공하면 Account와 새 세션 쿠키. 아이디 · 비밀번호가 틀리면 401 unauthorized. */
export function login(loginId, password) {
  return api.post('/api/v1/auth/login', { loginId, password });
}

/** 로그아웃. 세션을 끝낸다(204). */
export function logout() {
  return api.post('/api/v1/auth/logout');
}

/** 지금 로그인한 계정. 로그인 안 했으면 401 unauthorized. */
export function getMe() {
  return api.get('/api/v1/auth/me');
}
