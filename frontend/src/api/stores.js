import { api } from '@/api/client.js';

// 가게 가입(C5) · 가게 목록 · 위치(C6) — CONTRACTS_API.md 「1. 계정 · 가게 · 손님 · 알림 · 분석」.
// 상인회 API는 서버가 로그인 계정의 권역으로 범위를 정한다(다른 권역 가게는 404).

/** 업종 enum(CONTRACTS.md 「3」 store.category)과 화면 글자 */
export const STORE_CATEGORY_LABELS = Object.freeze({
  restaurant: '음식점',
  cafe: '카페',
  dessert: '디저트',
  pub: '주점',
  workshop: '공방',
  bookstore: '서점',
  gift_shop: '소품샵',
  stationery: '문구점',
});

/** 소속 골목 enum(store.alley) */
export const STORE_ALLEY_LABELS = Object.freeze({
  front_gate: '정문 앞',
  center: '중앙',
  back_street: '뒷길',
});

/** 영업 상태 enum(store.businessStatus). 사장님이 바꾼다 */
export const BUSINESS_STATUS_LABELS = Object.freeze({
  open: '영업',
  paused: '휴업',
  closed: '운영 중단',
});

/** 가입 상태 enum(store.joinStatus) */
export const JOIN_STATUS_LABELS = Object.freeze({
  requested: '신청',
  approved: '승인',
  rejected: '거절',
  withdraw_requested: '탈퇴 신청',
  withdrawn: '탈퇴',
});

/** 휴무일 enum(store.closedDays) 순서대로 */
export const WEEKDAY_LABELS = Object.freeze({ mon: '월', tue: '화', wed: '수', thu: '목', fri: '금', sat: '토', sun: '일' });

/** 위치 수정 입력 범위(C6). 서버가 다시 검사하고, 화면은 미리 알려 주는 데만 쓴다. */
export const LOCATION_LIMITS = Object.freeze({ addressMaxLength: 200, mapMin: -999.9999, mapMax: 999.9999, mapScale: 4 });

/** 권역의 참여 가게 목록(승인 · 탈퇴 신청). 페이지 없이 전부. @returns {Promise<{ items: object[] }>} */
export function getStores(regionId) {
  return api.get('/api/v1/stores', { query: { regionId } });
}

/** 가게 위치 · 주소 수정(상인회). 응답은 고친 Store. */
export function updateStoreLocation(storeId, { address, alley, mapX, mapY }) {
  return api.put(`/api/v1/stores/${encodeURIComponent(storeId)}/location`, { address, alley, mapX, mapY });
}

/** 내 권역의 가게 가입 목록(상인회). 오래된 신청부터, 공통 페이지. */
export function getStoreJoins({ status = 'requested', page = 0, size = 20 } = {}) {
  return api.get('/api/v1/store-joins', { query: { status, page, size } });
}

/** 가입 승인. 응답은 StoreJoin. */
export function approveStoreJoin(storeId) {
  return api.post(`/api/v1/store-joins/${encodeURIComponent(storeId)}/approve`);
}

/** 가입 거절. 응답은 StoreJoin. */
export function rejectStoreJoin(storeId) {
  return api.post(`/api/v1/store-joins/${encodeURIComponent(storeId)}/reject`);
}
