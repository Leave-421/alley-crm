import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';

// CONTRACTS_API.md 「2. 예약 · 잔액 · 수수료 · 정산 · 서버 시계 (담당 D)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.

// 시연용 시각 조작(D18). 목은 시연 도구가 켜진 서버처럼 동작한다. 새로고침하면 실제 시각으로 돌아간다.
let offsetMs = 0;

/** 한 요청 안에서는 같은 '지금'을 쓴다(맞춘 시각이 밀리초 차이로 1분 전으로 보이지 않게). */
function clockResponse(realNowMs = Date.now()) {
  return HttpResponse.json({
    now: new Date(realNowMs + offsetMs).toISOString(),
    offsetSeconds: Math.round(offsetMs / 1000),
  });
}

export const reservationHandlers = [
  http.get('/api/v1/demo/clock', () => rejectAnonymous() ?? clockResponse()),

  http.post('/api/v1/demo/clock', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    const hasAdvance = Number.isInteger(body?.advanceMinutes);
    const hasAt = typeof body?.at === 'string' && !Number.isNaN(Date.parse(body.at));
    if (hasAdvance === hasAt) return errorResponse(400, 'invalid', 'advanceMinutes와 at 중 하나만 보내 주세요.');
    const realNowMs = Date.now();
    offsetMs = hasAdvance ? offsetMs + body.advanceMinutes * 60_000 : Date.parse(body.at) - realNowMs;
    return clockResponse(realNowMs);
  }),

  http.delete('/api/v1/demo/clock', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectAnonymous();
    if (blocked) return blocked;
    offsetMs = 0;
    return clockResponse();
  }),
];
