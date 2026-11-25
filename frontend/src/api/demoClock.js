import { api } from '@/api/client.js';

// 시연용 시각 조작 — CONTRACTS_API.md 「시연용 시각 조작 (D18)」.
// 서버가 DEMO_TOOLS_ENABLED=true일 때만 있고, 꺼져 있으면 404 not_found. 로그인한 누구나 쓸 수 있다.
// 응답 ClockResponse: { now: ISO(UTC), offsetSeconds }

export function getClock() {
  return api.get('/api/v1/demo/clock');
}

/** 분 단위로 앞당긴다(음수면 되돌림). */
export function advanceClock(minutes) {
  return api.post('/api/v1/demo/clock', { advanceMinutes: minutes });
}

/** 특정 시각(UTC ISO)으로 맞춘다. */
export function setClock(atIso) {
  return api.post('/api/v1/demo/clock', { at: atIso });
}

/** 실제 시각으로 되돌린다. */
export function resetClock() {
  return api.delete('/api/v1/demo/clock');
}
