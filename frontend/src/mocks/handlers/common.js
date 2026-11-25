import { http, HttpResponse } from 'msw/http';

// CONTRACTS_API.md 「0. 공통」(C1)

export const commonHandlers = [
  // GET /api/v1/csrf — 응답 본문과 함께 XSRF-TOKEN 쿠키를 내려준다
  http.get('/api/v1/csrf', ({ cookies }) => {
    const token = cookies['XSRF-TOKEN'] ?? crypto.randomUUID();
    return HttpResponse.json(
      { headerName: 'X-XSRF-TOKEN', token },
      { headers: { 'Set-Cookie': `XSRF-TOKEN=${token}; Path=/; SameSite=Lax` } },
    );
  }),
];
