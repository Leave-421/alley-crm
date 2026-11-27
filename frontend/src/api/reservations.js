import { api } from '@/api/client.js';

// 손님 예약 신청 · 예약 가능 시간 — CONTRACTS_API.md 「2. 예약」(D2 · D5).

/** 예약 상태 enum(CONTRACTS.md 「3」 reservation.status). 노쇼 상태는 없다 */
export const RESERVATION_STATUS_LABELS = Object.freeze({ requested: '신청', confirmed: '확정', visited: '방문 완료', canceled: '취소' });

/** 취소 사유 enum(CONTRACTS.md 「3」 reservation.cancelReason) */
export const CANCEL_REASON_LABELS = Object.freeze({ customer_canceled: '내가 취소', store_rejected: '가게 거절', auto_canceled: '자동 취소' });

/**
 * 회원의 내 예약(D13). 묶음 목록 — 다음 코스 예약은 기준 예약과 한 묶음(courseRootId), 따로 한 예약은 혼자 한 묶음.
 * @returns {Promise<{ items: { courseRootId: string | null, reservations: object[] }[] }>}
 */
export function getMyReservations() {
  return api.get('/api/v1/reservations/mine');
}

/**
 * 회원 손님 취소(D6). 24시간 전까지 100%, 그 뒤 가게 취소 마감 전까지 가게 비율 환불, 마감 뒤는 409 conflict.
 * 응답은 Reservation(canceled · customer_canceled · refundAmount).
 */
export function cancelReservation(reservationId) {
  return api.post(`/api/v1/reservations/${encodeURIComponent(reservationId)}/cancel`);
}

/**
 * 회원 손님 예약 변경(D8). 확정 예약만, 가게 취소 마감 전까지. 바뀐 값만 보낸다(둘 중 하나 이상).
 * 결과는 status 'requested' + pendingStartAt · pendingPartySize(사장님 승인 대기, 돈은 그대로).
 */
export function changeReservation(reservationId, { startAt, partySize }) {
  return api.post(`/api/v1/reservations/${encodeURIComponent(reservationId)}/change`, {
    ...(startAt ? { startAt } : {}),
    ...(partySize ? { partySize } : {}),
  });
}

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
export function getAvailableSlots(storeId, date, after = null) {
  // after: 다음 코스(D12)의 기준 예약 시각. 같은 날은 그보다 늦은 슬롯만 온다
  return api.get(`/api/v1/stores/${encodeURIComponent(storeId)}/available-slots`, { query: after ? { date, after } : { date } });
}

/**
 * 예약 신청. 로그인한 회원이면 회원 예약, 아니면 비회원 예약(guest 필수). 응답은 Reservation(status 'requested').
 * 다음 코스(D12)면 courseRootId(기준 예약) · courseProposalId(정식 제안, 없으면 null)를 넣는다.
 * 실패 code: invalid · not_found · slot_unavailable · insufficient_balance · conflict · forbidden_role.
 */
export function createReservation({ storeId, startAt, partySize, requestNote = null, guest = null, couponId = null, courseRootId = null, courseProposalId = null }) {
  return api.post('/api/v1/reservations', {
    storeId,
    startAt,
    partySize,
    requestNote,
    shareConsent: true,
    guest,
    couponId,
    courseRootId,
    courseProposalId,
  });
}
