import { api } from '@/api/client.js';

// 계정 API — CONTRACTS_API.md 「1. 계정」(C2). 응답 Account: { id, loginId, role, storeId, regionId }

/** 성별 · 연령대 값(CONTRACTS.md 「3」). 응답 안 함 · 모름은 null. */
export const GENDERS = Object.freeze([
  { value: 'female', label: '여성' },
  { value: 'male', label: '남성' },
]);
export const AGE_GROUPS = Object.freeze([
  { value: 'age_20_or_under', label: '20대 이하' },
  { value: 'age_30s', label: '30대' },
  { value: 'age_40s', label: '40대' },
  { value: 'age_50_or_over', label: '50대 이상' },
]);

/** 회원가입 입력 규칙(C2 계약). 서버가 다시 검사한다. */
export const SIGNUP_RULES = Object.freeze({
  loginIdPattern: /^[a-z0-9]{4,30}$/,
  passwordMin: 8,
  passwordMax: 64,
  nameMax: 20,
});

/** 손님 회원가입. 가입만 하고 로그인 세션은 만들지 않는다(201 Account). 이미 쓰는 아이디면 409 conflict. */
export function signup({ loginId, password, name, gender, ageGroup }) {
  return api.post('/api/v1/auth/signup', { loginId, password, name, gender, ageGroup });
}

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
