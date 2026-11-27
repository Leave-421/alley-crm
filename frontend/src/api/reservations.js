import { api } from '@/api/client.js';

// 손님 예약 신청 · 예약 가능 시간 — CONTRACTS_API.md 「2. 예약」(D2 · D5).

/** 성별 enum(CONTRACTS.md 「3」 gender). 응답 안 함은 null */
export const GENDER_LABELS = Object.freeze({ female: '여성', male: '남성' });

/** 연령대 enum(CONTRACTS.md 「3」 ageGroup). 모르면 null */
export const AGE_GROUP_LABELS = Object.freeze({
  age_20_or_under: '20대 이하',
  age_30s: '30대',
  age_40s: '40대',
  age_50_or_over: '50대 이상',
});

/** 비회원 입력 범위(D2). 서버가 다시 검사하고, 화면은 미리 알려 주는 데만 쓴다. */
export const GUEST_LIMITS = Object.freeze({ nameMaxLength: 20, phonePattern: /^\d{10,11}$/ });

/**
 * 그 가게 그 날짜(운영 시간대 기준 YYYY-MM-DD)의 예약 가능 시간. 로그인 불필요.
 * @returns {Promise<{ date: string, acceptingReservations: boolean, maxPartySize: number | null, depositPerPerson: number, slots: { startAt: string, available: boolean }[] }>}
 */
export function getAvailableSlots(storeId, date) {
  return api.get(`/api/v1/stores/${encodeURIComponent(storeId)}/available-slots`, { query: { date } });
}

/**
 * 예약 신청. 로그인한 회원이면 회원 예약, 아니면 비회원 예약(guest 필수). 응답은 Reservation(status 'requested').
 * 실패 code: invalid · not_found · slot_unavailable · insufficient_balance · conflict · forbidden_role.
 */
export function createReservation({ storeId, startAt, partySize, requestNote = null, guest = null, couponId = null }) {
  return api.post('/api/v1/reservations', {
    storeId,
    startAt,
    partySize,
    requestNote,
    shareConsent: true,
    guest,
    couponId,
    courseRootId: null,
    courseProposalId: null,
  });
}
