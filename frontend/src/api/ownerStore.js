import { api } from '@/api/client.js';

// 사장님 내 가게 — CONTRACTS_API.md 「사장님 내 가게 (C22)」. 가게는 서버가 로그인 계정으로 정한다.
// 세 API 모두 응답은 C6의 Store. 승인 전 · 거절 가게의 사장님은 403 forbidden_role.

/** 내 가게(Store) */
export function getMyStore() {
  return api.get('/api/v1/owner/store');
}

/** 영업 상태(open · paused · closed) · 예약 받기. 둘 다 보낸다. 응답은 Store. */
export function updateMyStoreStatus({ businessStatus, reservationEnabled }) {
  return api.put('/api/v1/owner/store/status', { businessStatus, reservationEnabled });
}
