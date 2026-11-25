import { http, HttpResponse } from 'msw/http';
import { DEMO_ROLES } from '@/demo/demoAccounts.js';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount, setMockAccount } from '../session.js';

// CONTRACTS_API.md 「1. 계정 · 가게 · 손님 · 알림 · 분석 (담당 C)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

// 목에 있는 계정 = 시연 계정(demo/demoAccounts.js). 비밀번호는 목에서 검사하지 않는다(빈 값만 거절).
const MOCK_ACCOUNTS = DEMO_ROLES.filter((item) => item.loginId).map((item, index) => ({
  id: String(index + 1),
  loginId: item.loginId,
  role: item.role,
  storeId: item.role === 'owner' ? '3' : null,
  regionId: item.role === 'association' ? '1' : null,
}));

export const accountHandlers = [
  // POST /api/v1/auth/login (C2)
  http.post('/api/v1/auth/login', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const body = await info.request.json().catch(() => null);
    if (!body?.loginId || !body?.password) return errorResponse(400, 'invalid', '아이디와 비밀번호를 넣어 주세요.');
    const account = MOCK_ACCOUNTS.find((item) => item.loginId === body.loginId);
    if (!account) return errorResponse(401, 'unauthorized', '아이디 또는 비밀번호가 맞지 않아요.');
    setMockAccount(account);
    return HttpResponse.json(account);
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
