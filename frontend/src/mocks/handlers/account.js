import { http, HttpResponse } from 'msw/http';
import { DEMO_ROLES } from '@/demo/demoAccounts.js';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount, setMockAccount } from '../session.js';

// CONTRACTS_API.md 「1. 계정 · 가게 · 손님 · 알림 · 분석 (담당 C)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

// 목 계정 = 시연 계정(demo/demoAccounts.js, 비밀번호는 검사하지 않음) + 이 탭에서 회원가입한 계정(비밀번호 검사).
// 메모리에만 있어서 새로고침하면 가입한 계정은 사라진다.
let accounts = DEMO_ROLES.filter((item) => item.loginId).map((item, index) => ({
  account: {
    id: String(index + 1),
    loginId: item.loginId,
    role: item.role,
    storeId: item.role === 'owner' ? '3' : null,
    regionId: item.role === 'association' ? '1' : null,
  },
  password: null,
}));

const GENDERS = ['female', 'male'];
const AGE_GROUPS = ['age_20_or_under', 'age_30s', 'age_40s', 'age_50_or_over'];

/** 계약 「POST /api/v1/auth/signup」의 입력 규칙. 맞으면 true. */
function isValidSignup(body) {
  if (!body || typeof body !== 'object') return false;
  if (typeof body.loginId !== 'string' || !/^[a-z0-9]{4,30}$/.test(body.loginId)) return false;
  if (typeof body.password !== 'string' || body.password.length < 8 || body.password.length > 64) return false;
  if (typeof body.name !== 'string' || body.name.length < 1 || body.name.length > 20) return false;
  if (body.gender != null && !GENDERS.includes(body.gender)) return false;
  if (body.ageGroup != null && !AGE_GROUPS.includes(body.ageGroup)) return false;
  return true;
}

export const accountHandlers = [
  // POST /api/v1/auth/signup (C2) — 손님 계정만 만들고 로그인 세션은 만들지 않는다
  http.post('/api/v1/auth/signup', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const body = await info.request.json().catch(() => null);
    if (!isValidSignup(body)) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    if (accounts.some((item) => item.account.loginId === body.loginId)) {
      return errorResponse(409, 'conflict', '이미 쓰는 아이디예요.');
    }
    const account = { id: String(accounts.length + 1), loginId: body.loginId, role: 'customer', storeId: null, regionId: null };
    accounts = [...accounts, { account, password: body.password }];
    return HttpResponse.json(account, { status: 201 });
  }),

  // POST /api/v1/auth/login (C2)
  http.post('/api/v1/auth/login', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const body = await info.request.json().catch(() => null);
    if (!body?.loginId || !body?.password) return errorResponse(400, 'invalid', '아이디와 비밀번호를 넣어 주세요.');
    const found = accounts.find((item) => item.account.loginId === body.loginId);
    if (!found || (found.password !== null && found.password !== body.password)) {
      return errorResponse(401, 'unauthorized', '아이디 또는 비밀번호가 맞지 않아요.');
    }
    setMockAccount(found.account);
    return HttpResponse.json(found.account);
  }),

  // POST /api/v1/auth/logout (C2)
  http.post('/api/v1/auth/logout', (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const anonymous = rejectAnonymous();
    if (anonymous) return anonymous;
    setMockAccount(null);
    return new HttpResponse(null, { status: 204 });
  }),

  // GET /api/v1/auth/me (C2)
  http.get('/api/v1/auth/me', () => rejectAnonymous() ?? HttpResponse.json(getMockAccount())),
];

/** 다른 목(가게 가입 신청 C5)이 사장님 계정을 만들 때 쓴다. 이미 쓰는 아이디면 null. */
export function addMockAccount({ loginId, password, role, storeId = null, regionId = null }) {
  if (accounts.some((item) => item.account.loginId === loginId)) return null;
  const account = { id: String(accounts.length + 1), loginId, role, storeId, regionId };
  accounts = [...accounts, { account, password }];
  return account;
}
