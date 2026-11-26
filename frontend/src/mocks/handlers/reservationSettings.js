import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';

// CONTRACTS_API.md 「2」의 「예약 운영 설정 · 슬롯 (D5)」 GET · PUT /api/v1/owner/reservation-settings 목.
// 로그인한 사장님 가게 하나의 설정을 메모리에 둔다(새로고침하면 처음 값). 권역 환불 비율은 목 값 0.5.

const REGION_LATE_CANCEL_REFUND_RATE = 0.5;

let settings = {
  slotMinutes: 60,
  capacityPerSlot: 2,
  maxPartySize: null,
  bookingWindowDays: 30,
  cancelDeadlineHours: 3,
  lateCancelRefundRate: null,
  depositPerPerson: 0,
};

const toResponse = () => ({ ...settings, effectiveLateCancelRefundRate: settings.lateCancelRefundRate ?? REGION_LATE_CANCEL_REFUND_RATE });

function rejectNonOwner() {
  return rejectAnonymous() ?? (getMockAccount().role === 'owner' ? null : errorResponse(403, 'forbidden_role', '사장님만 쓸 수 있어요.'));
}

const isIntIn = (value, min, max) => Number.isInteger(value) && value >= min && value <= max;

/** 계약 D5 표의 범위 · 칸 빠짐 검사 */
function isValid(body) {
  if (!body || typeof body !== 'object') return false;
  const rate = body.lateCancelRefundRate;
  return (
    isIntIn(body.slotMinutes, 10, 240) &&
    body.slotMinutes % 10 === 0 &&
    isIntIn(body.capacityPerSlot, 1, 50) &&
    'maxPartySize' in body &&
    (body.maxPartySize === null || isIntIn(body.maxPartySize, 1, 100)) &&
    isIntIn(body.bookingWindowDays, 1, 60) &&
    isIntIn(body.cancelDeadlineHours, 0, 24) &&
    'lateCancelRefundRate' in body &&
    (rate === null || (typeof rate === 'number' && rate >= 0 && rate <= 1 && Math.abs(Math.round(rate * 1000) - rate * 1000) < 1e-6)) &&
    isIntIn(body.depositPerPerson, 0, 1_000_000)
  );
}

export const reservationSettingsHandlers = [
  http.get('/api/v1/owner/reservation-settings', () => rejectNonOwner() ?? HttpResponse.json(toResponse())),

  http.put('/api/v1/owner/reservation-settings', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    if (!isValid(body)) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    settings = {
      slotMinutes: body.slotMinutes,
      capacityPerSlot: body.capacityPerSlot,
      maxPartySize: body.maxPartySize,
      bookingWindowDays: body.bookingWindowDays,
      cancelDeadlineHours: body.cancelDeadlineHours,
      lateCancelRefundRate: body.lateCancelRefundRate,
      depositPerPerson: body.depositPerPerson,
    };
    return HttpResponse.json(toResponse());
  }),
];
