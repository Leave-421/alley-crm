import { api } from '@/api/client.js';

// 예약 운영 설정 — CONTRACTS_API.md 「예약 운영 설정 · 슬롯 (D5)」 GET · PUT /api/v1/owner/reservation-settings.
// 가게는 서버가 로그인한 사장님 계정으로 정한다. PUT은 모든 칸을 보낸다(부분 수정 없음).
// 영업시간 · 휴무일(O-12)과 영업 상태 · 예약 받기는 C22 API가 맡는다.

/** 입력 범위(계약 D5 표). 서버가 다시 검사하고, 화면은 미리 알려 주는 데만 쓴다. */
export const SETTINGS_LIMITS = Object.freeze({
  slotMinutes: { min: 10, max: 240, step: 10 },
  capacityPerSlot: { min: 1, max: 50 },
  maxPartySize: { min: 1, max: 100 }, // null = 제한 없음
  bookingWindowDays: { min: 1, max: 60, default: 30 },
  cancelDeadlineHours: { min: 0, max: 24 }, // 24시간 전보다 이르게 못 잡음(FR-RSV-05 #1)
  lateCancelRefundRate: { min: 0, max: 1, scale: 3 }, // null = 권역 설정값
  depositPerPerson: { min: 0, max: 1_000_000 },
});

/** 지금 설정 + effectiveLateCancelRefundRate(실제로 쓰는 환불 비율) */
export function getReservationSettings() {
  return api.get('/api/v1/owner/reservation-settings');
}

/** 모든 칸을 보낸다. 응답은 저장된 설정. */
export function saveReservationSettings(settings) {
  const { slotMinutes, capacityPerSlot, maxPartySize, bookingWindowDays, cancelDeadlineHours, lateCancelRefundRate, depositPerPerson } = settings;
  return api.put('/api/v1/owner/reservation-settings', {
    slotMinutes,
    capacityPerSlot,
    maxPartySize,
    bookingWindowDays,
    cancelDeadlineHours,
    lateCancelRefundRate,
    depositPerPerson,
  });
}
