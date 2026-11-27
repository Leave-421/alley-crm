import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectAnonymous, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';

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

// 손님 예약 신청(D2) · 예약 가능 시간(D5)의 목 값. 가게는 골목 화면과 같은 목 가게 목록(map/storeCatalog.js)
const MOCK_MAX_PARTY_SIZE = 4;
const MOCK_DEPOSIT_PER_PERSON = 5000;
const SLOT_MINUTES = 30;
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const RESERVATION_NO_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 헷갈리는 0 O 1 I 제외(D2)

/** 영업일(운영 시간대 날짜)의 슬롯. 종료가 시작보다 이르면 다음 날 새벽까지. 몇 칸은 다 찬 것으로 둔다 */
function mockSlots(store, date, nowMs) {
  if (!store.openTime || !store.closeTime) return [];
  const weekday = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'][new Date(`${date}T00:00:00Z`).getUTCDay()];
  if ((store.closedDays ?? []).includes(weekday)) return [];
  const toMinutes = (time) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
  const open = toMinutes(store.openTime);
  let close = toMinutes(store.closeTime);
  if (close <= open) close += 24 * 60;
  const dayStartUtc = Date.parse(`${date}T00:00:00Z`) - KST_OFFSET_MS;
  const slots = [];
  for (let minute = open, index = 0; minute + SLOT_MINUTES <= close; minute += SLOT_MINUTES, index += 1) {
    const startMs = dayStartUtc + minute * 60_000;
    slots.push({ startAt: new Date(startMs).toISOString(), available: startMs > nowMs && index % 5 !== 3 });
  }
  return slots;
}

function mockReservationNo() {
  return Array.from({ length: 8 }, () => RESERVATION_NO_CHARS[Math.floor(Math.random() * RESERVATION_NO_CHARS.length)]).join('');
}

const serviceDay = (ms) => new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 10);

// 이 탭에서 신청한 손님 예약(다음 코스의 기준 예약 검사 · 정식 코스 제안 목이 쓴다). 새로고침하면 비워진다
const customerReservations = new Map();

/** 목 손님 예약 하나(정식 코스 제안 목 recommendation.js가 기준 예약을 찾을 때 쓴다) */
export function findMockCustomerReservation(id) {
  return customerReservations.get(id) ?? null;
}

// 내 예약(D13) 목: 회원이 처음 열 때 그 계정의 예약을 몇 건 깔아 둔다(코스 묶음 · 기준 예약 거절 · 지난 예약 · 취소 사유를 다 볼 수 있게)
const seededAccounts = new Set();
const DAY_MS = 86_400_000;

/** 운영 시간대 날짜(오늘 + days)의 HH:mm → UTC ISO */
function kstAt(days, time) {
  const day = serviceDay(Date.now() + offsetMs + days * DAY_MS);
  return new Date(Date.parse(`${day}T${time}:00Z`) - KST_OFFSET_MS).toISOString();
}

function seedMyReservations(accountId) {
  if (seededAccounts.has(accountId)) return;
  seededAccounts.add(accountId);
  const base = { partySize: 2, cancelReason: null, requestNote: null, coupon: null, courseRootId: null, fromRecommendation: false, visitAmount: null, visitedAt: null, refundAmount: null, pendingStartAt: null, pendingPartySize: null };
  const rows = [
    // 코스 묶음: 기준 가게 예약은 가게가 거절했지만 다음 코스 예약은 그대로(FR-RSV-12)
    { id: `m-${accountId}-10`, storeId: 'S03', startAt: kstAt(1, '18:00'), status: 'canceled', cancelReason: 'store_rejected', depositAmount: 10000, refundAmount: 10000 },
    { id: `m-${accountId}-11`, storeId: 'S06', startAt: kstAt(1, '19:30'), status: 'confirmed', courseRootId: `m-${accountId}-10`, depositAmount: 10000 },
    { id: `m-${accountId}-20`, storeId: 'S11', startAt: kstAt(3, '18:30'), status: 'requested', partySize: 3, depositAmount: 15000 },
    // 곧 시작 · 가게 취소 마감이 지난 확정 예약(취소 · 변경 잠김을 볼 수 있게)
    { id: `m-${accountId}-21`, storeId: 'S04', startAt: new Date(Date.now() + offsetMs + 90 * 60_000).toISOString(), status: 'confirmed', depositAmount: 10000 },
    // 변경 승인 대기: 원래 19:00 · 2명 → 바꾼 20:00 · 4명(D8)
    { id: `m-${accountId}-22`, storeId: 'S09', startAt: kstAt(4, '19:00'), status: 'requested', depositAmount: 10000, pendingStartAt: kstAt(4, '20:00'), pendingPartySize: 4 },
    { id: `m-${accountId}-30`, storeId: 'S07', startAt: kstAt(-5, '12:00'), status: 'visited', depositAmount: 0, visitAmount: 32000, visitedAt: kstAt(-5, '12:05') },
    { id: `m-${accountId}-31`, storeId: 'S13', startAt: kstAt(-9, '19:00'), status: 'canceled', cancelReason: 'customer_canceled', depositAmount: 10000, refundAmount: 10000 },
    { id: `m-${accountId}-32`, storeId: 'S02', startAt: kstAt(-12, '15:00'), status: 'canceled', cancelReason: 'auto_canceled', depositAmount: 10000 },
  ];
  for (const row of rows) {
    customerReservations.set(row.id, { ...base, reservationNo: mockReservationNo(), requestedAt: kstAt(-14, '10:00'), ...row, accountId });
  }
}

// 목 가게의 취소 마감(예약 시각 − 2시간)과 하루 전이 지난 뒤 환불 비율(D6). 실제 값은 가게 · 권역 설정
const MOCK_CANCEL_DEADLINE_MS = 2 * 60 * 60_000;
const MOCK_LATE_REFUND_RATE = 0.5;

/** 지금 취소하면 돌려받는 예약금. 마감이 지났거나 신청 · 확정이 아니면 null(취소 불가) */
function refundIfCanceledNow(reservation) {
  const nowMs = Date.now() + offsetMs;
  const startMs = Date.parse(reservation.startAt);
  const active = reservation.status === 'requested' || reservation.status === 'confirmed';
  if (!active || startMs - nowMs <= MOCK_CANCEL_DEADLINE_MS) return null;
  return startMs - nowMs >= DAY_MS ? reservation.depositAmount : Math.floor(reservation.depositAmount * MOCK_LATE_REFUND_RATE);
}

/** 내 예약 한 줄(D13 모양): 가게 이름 · 지금 취소할 수 있는지 · 그때 환불액을 붙인다 */
function toMineRow(reservation) {
  const refund = refundIfCanceledNow(reservation);
  const { accountId: _accountId, guestPhone: _guestPhone, ...rest } = reservation; // 계정 ID · 전화번호는 응답에 넣지 않는다
  return {
    ...rest,
    storeName: MOCK_STORES.find((store) => store.id === reservation.storeId)?.name ?? '',
    cancelable: refund !== null,
    refundIfCanceled: refund,
  };
}

// 비회원 예약 조회(C13) 목: 시연용 비회원 예약. 예약번호 GSTK7Q2M + 전화번호 01098765432로 들어간다.
// 이 탭에서 비회원으로 신청한 예약도 그 번호 · 전화번호로 들어간다.
export const MOCK_GUEST_DEMO = Object.freeze({ reservationNo: 'GSTK7Q2M', phone: '01098765432' });
let guestSeeded = false;

function seedGuestReservations() {
  if (guestSeeded) return;
  guestSeeded = true;
  const base = { cancelReason: null, requestNote: null, coupon: null, courseRootId: null, fromRecommendation: false, visitAmount: null, visitedAt: null, refundAmount: null, pendingStartAt: null, pendingPartySize: null, requestedAt: kstAt(-4, '10:00'), accountId: null, guestPhone: MOCK_GUEST_DEMO.phone };
  const rows = [
    { id: 'g-1', reservationNo: MOCK_GUEST_DEMO.reservationNo, storeId: 'S05', startAt: kstAt(1, '12:00'), partySize: 2, status: 'confirmed', depositAmount: 10000 },
    { id: 'g-2', reservationNo: 'GSTP4WX9', storeId: 'S12', startAt: kstAt(2, '18:30'), partySize: 3, status: 'requested', depositAmount: 15000 },
    { id: 'g-3', reservationNo: 'GSTH8RN3', storeId: 'S08', startAt: kstAt(-3, '13:00'), partySize: 2, status: 'visited', depositAmount: 0, visitAmount: 24000, visitedAt: kstAt(-3, '13:10') },
  ];
  for (const row of rows) customerReservations.set(row.id, { ...base, ...row });
}

/** 예약번호(대소문자 무시) + 전화번호가 맞는 비회원 예약. 없거나 다르면 null(둘을 구분하지 않는다) */
export function findMockGuestReservation(reservationNo, phone) {
  seedGuestReservations();
  const no = String(reservationNo ?? '').toUpperCase();
  for (const item of customerReservations.values()) {
    if (item.guestPhone && item.reservationNo === no && item.guestPhone === phone) return toGuestReservation(item);
  }
  return null;
}

/** 그 전화번호의 비회원 예약 전체(Reservation 모양, 방문 일시가 늦은 것부터) */
export function listMockGuestReservations(phone) {
  seedGuestReservations();
  return [...customerReservations.values()]
    .filter((item) => item.guestPhone === phone)
    .sort((a, b) => Date.parse(b.startAt) - Date.parse(a.startAt))
    .map(toGuestReservation);
}

function toGuestReservation(item) {
  const { accountId: _accountId, guestPhone: _guestPhone, ...rest } = item;
  return rest;
}

/** 회원 손님의 자기 예약을 찾는다(남의 예약 · 없는 예약은 404, IDOR). 통과하면 { reservation } */
function findOwnReservation(id) {
  const blocked = rejectAnonymous();
  if (blocked) return { blocked };
  const account = getMockAccount();
  if (account.role !== 'customer') return { blocked: errorResponse(403, 'forbidden_role', '손님만 할 수 있어요.') };
  const reservation = customerReservations.get(id);
  if (!reservation || reservation.accountId !== account.id) return { blocked: errorResponse(404, 'not_found', '예약을 찾을 수 없어요.') };
  return { reservation };
}

/** 다음 코스(D12) 검사: 기준 예약이 없으면 not_found, 같은 가게 · 같은 날 기준 시각과 같거나 이르면 conflict. 통과하면 null */
function rejectBadCourse(body) {
  if (!body.courseRootId) return null;
  const root = customerReservations.get(body.courseRootId);
  if (!root) return errorResponse(404, 'not_found', '기준 예약을 찾을 수 없어요.');
  if (root.storeId === body.storeId) return errorResponse(409, 'conflict', '기준 예약과 같은 가게예요.');
  const sameDay = serviceDay(Date.parse(root.startAt)) === serviceDay(Date.parse(body.startAt));
  if (sameDay && Date.parse(body.startAt) <= Date.parse(root.startAt)) return errorResponse(409, 'conflict', '같은 날은 기준 예약보다 늦은 시간만 예약할 수 있어요.');
  return null;
}

/** 사장님 API의 역할 검사: 로그인 안 함 401, 사장님이 아니면 403 forbidden_role. 통과하면 null. */
function rejectNonOwner() {
  return rejectAnonymous() ?? (getMockAccount().role === 'owner' ? null : errorResponse(403, 'forbidden_role', '사장님만 볼 수 있어요.'));
}

// 날짜별 예약 목록(D4)의 목 예약. 어느 날짜를 열어도 상태 · 취소 사유 · 추천 여부 · 회원 여부를 모두 볼 수 있게
// 같은 하루치를 그 날짜 시각으로 만든다. 시각은 운영 시간대(Asia/Seoul) 벽시계.
const MOCK_DAY = [
  { time: '17:30', partySize: 2, status: 'visited', cancelReason: null, customerType: 'member', maskedName: '이*우', fromRecommendation: false, requestNote: null, depositAmount: 0 },
  { time: '18:00', partySize: 3, status: 'confirmed', cancelReason: null, customerType: 'member', maskedName: '김*연', fromRecommendation: true, requestNote: null, depositAmount: 15000 },
  { time: '18:30', partySize: 2, status: 'canceled', cancelReason: 'customer_canceled', customerType: 'member', maskedName: '박*율', fromRecommendation: false, requestNote: null, depositAmount: 10000 },
  { time: '19:00', partySize: 2, status: 'confirmed', cancelReason: null, customerType: 'guest', maskedName: '김*코', fromRecommendation: false, requestNote: '창가 자리면 좋겠어요', depositAmount: 0 },
  { time: '19:30', partySize: 4, status: 'canceled', cancelReason: 'store_rejected', customerType: 'guest', maskedName: '정*늘', fromRecommendation: true, requestNote: null, depositAmount: 20000 },
  { time: '20:00', partySize: 1, status: 'canceled', cancelReason: 'auto_canceled', customerType: 'guest', maskedName: null, fromRecommendation: false, requestNote: null, depositAmount: 0 },
  { time: '20:30', partySize: 2, status: 'requested', cancelReason: null, customerType: 'member', maskedName: '최*아', fromRecommendation: true, requestNote: '아이 의자 1개', depositAmount: 10000 },
  { time: '21:30', partySize: 3, status: 'requested', cancelReason: null, customerType: 'guest', maskedName: '한*름', fromRecommendation: false, requestNote: null, depositAmount: 0 },
  // 손님이 확정 예약을 바꾸겠다고 한 변경 신청(D8): status는 requested, 원래 값은 그대로, pending에 바꿀 값
  { time: '21:00', partySize: 2, status: 'requested', cancelReason: null, customerType: 'member', maskedName: '송*비', fromRecommendation: false, requestNote: null, depositAmount: 10000, pending: { time: '21:30', partySize: 4 } },
];

// 승인 · 거절(D3) · 방문 처리(D11)로 바뀐 칸. 예약 id → { status, cancelReason, visitAmount?, visitedAt?, time?, partySize?, changeDecided? }.
// 변경 신청(D8)을 승인하면 time · partySize가 바뀐 값으로, 거절하면 원래 값으로 confirmed가 된다.
// 새로고침하면 처음으로 돌아간다.
const decided = new Map();

// 적용된 쿠폰(목록 D4 응답에는 없는 서버 쪽 값). MOCK_DAY 순번 → 쿠폰. 사용 확인(E14)은 usedCoupons에 남긴다.
const MOCK_APPLIED_COUPONS = {
  0: { couponId: '501', benefitType: 'percent_discount', discountAmount: null, discountPercent: 10, maxDiscountAmount: 5000, serviceName: null },
  1: { couponId: '502', benefitType: 'fixed_discount', discountAmount: 3000, discountPercent: null, maxDiscountAmount: null, serviceName: null },
  6: { couponId: '503', benefitType: 'service', discountAmount: null, discountPercent: null, maxDiscountAmount: null, serviceName: '음료 1잔' },
};
const usedCoupons = new Set(); // 예약 id

// 손님이 '늦을 것 같아요'(D14)로 입력한 지연(분). MOCK_DAY 순번 → 분
const MOCK_DELAY_MINUTES = { 3: 20 };

// 소개 수수료율(설정값, NFR-04). 목은 기본값 4%
const REFERRAL_FEE_RATE = 0.04;

/**
 * 소개 확정 · 수수료(#246). 목은 '추천으로 온(정식 코스 제안) 예약이 방문 처리되면 소개 확정'으로 본다.
 * 수수료 = (방문 금액 − 실제 쿠폰 할인) × 수수료율, 1원 미만 버림(D15). 방문 전 · 소개 아님은 false · null.
 */
function referralOf({ status, fromRecommendation, coupon, visitAmount }) {
  if (status !== 'visited' || !fromRecommendation || visitAmount === null) return { referralConfirmed: false, referralFee: null };
  return { referralConfirmed: true, referralFee: Math.floor((visitAmount - discountFor(coupon, visitAmount)) * REFERRAL_FEE_RATE) };
}

/** 방문 할인(3.6 「방문 할인」 표) */
function discountFor(coupon, visitAmount) {
  if (!coupon || visitAmount === null) return 0;
  if (coupon.benefitType === 'fixed_discount') return Math.min(coupon.discountAmount, visitAmount);
  if (coupon.benefitType === 'percent_discount') {
    const amount = Math.floor((visitAmount * coupon.discountPercent) / 100);
    return coupon.maxDiscountAmount === null ? amount : Math.min(amount, coupon.maxDiscountAmount);
  }
  return 0;
}

// 실시간 알림 목(ownerNotificationStream.js)이 '새 예약'을 넣는 곳. 날짜 → 그날 더 들어온 예약들
const incomingByDate = new Map();
const INCOMING_TEMPLATE = { time: '22:00', partySize: 2, status: 'requested', cancelReason: null, customerType: 'guest', maskedName: '윤*솔', fromRecommendation: true, requestNote: '방금 들어온 예약', depositAmount: 0 };

/** 그날 새 신청 예약을 하나 더한다(목). 더한 예약 id를 돌려준다. */
export function addMockIncomingReservation(date) {
  const list = incomingByDate.get(date) ?? [];
  incomingByDate.set(date, [...list, { ...INCOMING_TEMPLATE, time: `22:${String(list.length * 10).padStart(2, '0')}` }]);
  return `${date.replaceAll('-', '')}-${MOCK_DAY.length + list.length + 1}`;
}

function mockOwnerReservations(date) {
  const day = date.replaceAll('-', '');
  return [...MOCK_DAY, ...(incomingByDate.get(date) ?? [])].map((item, index) => {
    const id = `${day}-${index + 1}`;
    const changed = decided.get(id) ?? {};
    const startAt = new Date(`${date}T${changed.time ?? item.time}:00+09:00`);
    // 변경 신청은 결정되기 전까지만 pending 값이 있다
    const pending = item.pending && !changed.changeDecided ? item.pending : null;
    const status = changed.status ?? item.status;
    const cancelReason = changed.status ? changed.cancelReason : item.cancelReason;
    // 처음부터 방문인 목 예약은 방문 금액 30,000원으로 본다
    const visitAmount = changed.visitAmount ?? (item.status === 'visited' ? 30000 : null);
    const visitedAt = changed.visitedAt ?? (item.status === 'visited' ? startAt.toISOString() : null);
    return {
      id,
      reservationNo: `K7Q2M9X${'ABCDEFGHJKLMNPQR'[index] ?? 'Z'}`, // 8자, 헷갈리는 0 O 1 I 없음(계약)
      startAt: startAt.toISOString(),
      partySize: changed.partySize ?? item.partySize,
      pendingStartAt: pending ? new Date(`${date}T${pending.time}:00+09:00`).toISOString() : null,
      pendingPartySize: pending ? pending.partySize : null,
      // 손님이 입력한 지연 예상 도착 시각(D14). 확정 예약에만 들어온다
      expectedArrivalAt: status === 'confirmed' && MOCK_DELAY_MINUTES[index] ? new Date(startAt.getTime() + MOCK_DELAY_MINUTES[index] * 60_000).toISOString() : null,
      status,
      cancelReason,
      requestedAt: new Date(startAt.getTime() - 26 * 3_600_000).toISOString(),
      requestNote: item.requestNote,
      depositAmount: item.depositAmount,
      customerType: item.customerType,
      maskedName: item.maskedName,
      fromRecommendation: item.fromRecommendation,
      // 적용 · 사용된 쿠폰. 방문 전 취소(거절 포함)면 적용 취소라 없다(3.5)
      coupon: status === 'canceled' ? null : (MOCK_APPLIED_COUPONS[index] ?? null),
      visitAmount,
      visitedAt,
      ...referralOf({ status, fromRecommendation: item.fromRecommendation, coupon: MOCK_APPLIED_COUPONS[index] ?? null, visitAmount }),
    };
  }).sort((a, b) => a.startAt.localeCompare(b.startAt)); // 시각 순(D4)
}

/** 목 예약 id('YYYYMMDD-n')로 그날 목록에서 찾는다. 없으면 null. */
function findMockReservation(id) {
  const match = /^(\d{4})(\d{2})(\d{2})-\d+$/.exec(id);
  if (!match) return null;
  return mockOwnerReservations(`${match[1]}-${match[2]}-${match[3]}`).find((item) => item.id === id) ?? null;
}

/** 목록 항목 → 계약의 Reservation 응답 모양(D2). 손님 이름 · 회원 여부는 넣지 않는다. */
function toReservation(item) {
  const { customerType: _customerType, maskedName: _maskedName, expectedArrivalAt: _expectedArrivalAt, ...rest } = item;
  return { ...rest, storeId: '3', courseRootId: null };
}

/** 승인 · 거절 공통: 신청 상태가 아니면 409, 승인은 예약 시각이 지났어도 409(자동 거절 대상). */
function decideReservation(next) {
  return (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const found = findMockReservation(info.params.id);
    if (!found) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    if (found.status !== 'requested') return errorResponse(409, 'conflict', '신청 상태인 예약만 처리할 수 있어요.');
    const nowMs = Date.now() + offsetMs;
    if (found.pendingStartAt) {
      // 변경 신청(D8): 원래 시각과 바뀐 시각 중 이른 쪽이 지났으면 변경만 자동 거절 대상
      if (Math.min(Date.parse(found.startAt), Date.parse(found.pendingStartAt)) <= nowMs) {
        return errorResponse(409, 'conflict', '변경 요청을 처리할 수 있는 시각이 지났어요. 변경은 자동 거절돼요.');
      }
      const approve = next.status === 'confirmed';
      const pendingTime = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(found.pendingStartAt));
      // 승인: 바꾼 값으로 확정(예약금 차액은 서버가 정산) · 거절: 원래 값으로 확정 유지(취소가 아님)
      const change = approve
        ? { status: 'confirmed', cancelReason: null, time: pendingTime, partySize: found.pendingPartySize, changeDecided: true }
        : { status: 'confirmed', cancelReason: null, changeDecided: true };
      decided.set(found.id, change);
      return HttpResponse.json(toReservation(findMockReservation(found.id)));
    }
    if (next.status === 'confirmed' && Date.parse(found.startAt) <= nowMs) {
      return errorResponse(409, 'conflict', '예약 시각이 지나 승인할 수 없어요. 자동 거절돼요.');
    }
    decided.set(found.id, next);
    const coupon = next.status === 'canceled' ? null : found.coupon; // 거절이면 쿠폰은 적용 취소
    return HttpResponse.json(toReservation({ ...found, ...next, coupon }));
  };
}

export const reservationHandlers = [
  // POST /api/v1/owner/reservations/{id}/visit (D11) — 확정 → 방문, 쿠폰은 바꾸지 않는다
  http.post('/api/v1/owner/reservations/:id/visit', async (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    const amount = body?.visitAmount;
    if (!Number.isInteger(amount) || amount < 0 || amount > 10_000_000) return errorResponse(400, 'invalid', '방문 금액을 확인해 주세요.');
    const found = findMockReservation(info.params.id);
    if (!found) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    if (found.status !== 'confirmed') return errorResponse(409, 'conflict', '확정된 예약만 방문 처리할 수 있어요.');
    const next = { status: 'visited', cancelReason: null, visitAmount: amount, visitedAt: new Date(Date.now() + offsetMs).toISOString() };
    decided.set(found.id, next);
    // 응답에 실제로 깎은 쿠폰 할인액(쿠폰이 없으면 null)
    const couponDiscountAmount = found.coupon ? discountFor(found.coupon, amount) : null;
    const referral = referralOf({ status: 'visited', fromRecommendation: found.fromRecommendation, coupon: found.coupon, visitAmount: amount });
    return HttpResponse.json({ ...toReservation({ ...found, ...next, ...referral }), couponDiscountAmount });
  }),

  // POST /api/v1/owner/reservations/{id}/coupon-use (E14 3.6) — 방문 처리된 예약의 적용 쿠폰 → 사용 완료
  http.post('/api/v1/owner/reservations/:id/coupon-use', (info) => {
    const blocked = rejectInvalidCsrf(info) ?? rejectNonOwner();
    if (blocked) return blocked;
    const found = findMockReservation(info.params.id);
    if (!found) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    if (found.status !== 'visited') return errorResponse(409, 'conflict', '방문 처리한 예약만 쿠폰 사용을 확인할 수 있어요.');
    if (!found.coupon) return errorResponse(409, 'conflict', '이 예약에 적용된 쿠폰이 없어요.');
    if (usedCoupons.has(found.id)) return errorResponse(409, 'conflict', '이미 사용 확인한 쿠폰이에요.');
    usedCoupons.add(found.id);
    return HttpResponse.json({
      couponId: found.coupon.couponId,
      reservationId: found.id,
      status: 'used',
      discountAmount: discountFor(found.coupon, found.visitAmount),
      usedAt: new Date(Date.now() + offsetMs).toISOString(),
    });
  }),

  // POST /api/v1/owner/reservations/{id}/confirm · /reject (D3)
  http.post('/api/v1/owner/reservations/:id/confirm', decideReservation({ status: 'confirmed', cancelReason: null })),
  http.post('/api/v1/owner/reservations/:id/reject', decideReservation({ status: 'canceled', cancelReason: 'store_rejected' })),

  // GET /api/v1/owner/reservations?date= (D4) — 로그인한 사장님 가게의 그 날짜 예약, 시각 순
  http.get('/api/v1/owner/reservations', ({ request }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const date = new URL(request.url).searchParams.get('date');
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) {
      return errorResponse(400, 'invalid', '날짜를 확인해 주세요.');
    }
    return HttpResponse.json({ date, referralFeeRate: REFERRAL_FEE_RATE, items: mockOwnerReservations(date) });
  }),

  // GET /api/v1/owner/reservations/{id} (D4) — 목록 항목과 같은 모양 + referralFeeRate
  http.get('/api/v1/owner/reservations/:id', ({ params }) => {
    const blocked = rejectNonOwner();
    if (blocked) return blocked;
    const found = findMockReservation(params.id);
    if (!found) return errorResponse(404, 'not_found', '예약을 찾을 수 없어요.');
    return HttpResponse.json({ ...found, referralFeeRate: REFERRAL_FEE_RATE });
  }),

  // GET /api/v1/reservations/mine (D13) — 회원 손님의 내 예약. 묶음 = 기준 예약 + 그 다음 코스들, 묶음은 가장 이른 시각 순 · 안은 시간순
  http.get('/api/v1/reservations/mine', () => {
    const blocked = rejectAnonymous();
    if (blocked) return blocked;
    const account = getMockAccount();
    if (account.role !== 'customer') return errorResponse(403, 'forbidden_role', '손님만 볼 수 있어요.');
    seedMyReservations(account.id);
    const mine = [...customerReservations.values()].filter((item) => item.accountId === account.id);
    const groups = new Map();
    for (const reservation of mine) {
      const key = reservation.courseRootId ?? reservation.id;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(reservation);
    }
    const items = [...groups.entries()].map(([key, list]) => {
      const sorted = list.sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt));
      return { courseRootId: sorted.length > 1 ? key : null, reservations: sorted.map(toMineRow) };
    });
    items.sort((a, b) => Date.parse(a.reservations[0].startAt) - Date.parse(b.reservations[0].startAt));
    return HttpResponse.json({ items });
  }),

  // POST /api/v1/reservations/{id}/cancel (D6) — 회원 손님 취소. 24시간 전 100%, 마감 전 가게 비율, 마감 뒤 409
  http.post('/api/v1/reservations/:id/cancel', (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const { blocked, reservation } = findOwnReservation(info.params.id);
    if (blocked) return blocked;
    const refund = refundIfCanceledNow(reservation);
    if (refund === null) return errorResponse(409, 'conflict', '취소할 수 없는 예약이에요.');
    Object.assign(reservation, { status: 'canceled', cancelReason: 'customer_canceled', refundAmount: refund, pendingStartAt: null, pendingPartySize: null });
    return HttpResponse.json(toMineRow(reservation));
  }),

  // POST /api/v1/reservations/{id}/change (D8) — 확정만, 마감 전까지. 신청으로 되돌리고 바꿀 값은 pending 칸에(돈은 그대로)
  http.post('/api/v1/reservations/:id/change', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const { blocked, reservation } = findOwnReservation(info.params.id);
    if (blocked) return blocked;
    const body = await info.request.json().catch(() => null);
    const startAt = body?.startAt ?? null;
    const partySize = body?.partySize ?? null;
    const changed = (startAt && startAt !== reservation.startAt) || (partySize && partySize !== reservation.partySize);
    if (!changed || (partySize !== null && (!Number.isInteger(partySize) || partySize < 1 || partySize > MOCK_MAX_PARTY_SIZE))) {
      return errorResponse(400, 'invalid', '바뀐 내용을 확인해 주세요.');
    }
    if (reservation.status !== 'confirmed' || reservation.pendingStartAt || reservation.pendingPartySize || refundIfCanceledNow(reservation) === null) {
      return errorResponse(409, 'conflict', '지금은 변경할 수 없는 예약이에요.');
    }
    if (startAt && Date.parse(startAt) <= Date.now() + offsetMs) return errorResponse(409, 'slot_unavailable', '예약할 수 없는 시간이에요.');
    Object.assign(reservation, { status: 'requested', pendingStartAt: startAt ?? reservation.startAt, pendingPartySize: partySize ?? reservation.partySize });
    return HttpResponse.json(toMineRow(reservation));
  }),

  // GET /api/v1/stores/{storeId}/available-slots?date= (D5) — 누구나. 가게 영업시간을 슬롯 30분으로 나눈다
  http.get('/api/v1/stores/:storeId/available-slots', ({ params, request }) => {
    const store = MOCK_STORES.find((item) => item.id === params.storeId);
    if (!store) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    const date = new URL(request.url).searchParams.get('date');
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) {
      return errorResponse(400, 'invalid', '날짜를 확인해 주세요.');
    }
    // after(다음 코스의 기준 예약 시각): 같은 날은 그보다 늦은 슬롯만 돌려준다. 다른 날은 영향 없음(D12)
    const after = new URL(request.url).searchParams.get('after');
    const afterMs = after ? Date.parse(after) : NaN;
    const slots = mockSlots(store, date, Date.now() + offsetMs).filter(
      (slot) => Number.isNaN(afterMs) || serviceDay(afterMs) !== date || Date.parse(slot.startAt) > afterMs,
    );
    return HttpResponse.json({
      date,
      acceptingReservations: true,
      maxPartySize: MOCK_MAX_PARTY_SIZE,
      depositPerPerson: MOCK_DEPOSIT_PER_PERSON,
      slots,
    });
  }),

  // POST /api/v1/reservations (D2) — 손님(로그인 불필요). 회원이면 회원 예약, 아니면 비회원 예약
  http.post('/api/v1/reservations', async (info) => {
    const blocked = rejectInvalidCsrf(info);
    if (blocked) return blocked;
    // 비회원 제한 세션(C13)에서 한 신청도 비회원 신청이다
    const signedIn = getMockAccount();
    if (signedIn && signedIn.role !== 'customer' && signedIn.role !== 'guest') return errorResponse(403, 'forbidden_role', '손님 계정으로만 예약할 수 있어요.');
    const account = signedIn?.role === 'customer' ? signedIn : null;
    const body = await info.request.json().catch(() => null);
    const guest = body?.guest;
    const guestOk = account || (guest && typeof guest.name === 'string' && guest.name.trim().length >= 1 && guest.name.trim().length <= 20 && /^\d{10,11}$/.test(guest.phone ?? ''));
    if (!body?.storeId || !body?.startAt || !Number.isInteger(body?.partySize) || body.partySize < 1 || body.partySize > MOCK_MAX_PARTY_SIZE || body.shareConsent !== true || !guestOk) {
      return errorResponse(400, 'invalid', '입력한 내용을 확인해 주세요.');
    }
    if (!MOCK_STORES.some((item) => item.id === body.storeId)) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    const nowIso = new Date(Date.now() + offsetMs).toISOString();
    if (Date.parse(body.startAt) <= Date.now() + offsetMs) return errorResponse(409, 'slot_unavailable', '예약할 수 없는 시간이에요.');
    const badCourse = rejectBadCourse(body);
    if (badCourse) return badCourse;
    const reservation = {
      id: String(Date.now()),
      reservationNo: mockReservationNo(),
      storeId: body.storeId,
      startAt: body.startAt,
      partySize: body.partySize,
      status: 'requested',
      cancelReason: null,
      requestedAt: nowIso,
      requestNote: body.requestNote ?? null,
      depositAmount: MOCK_DEPOSIT_PER_PERSON * body.partySize,
      // 쿠폰은 회원만(비회원이 보내면 무시). 목 쿠폰은 coupon.js의 사용 가능 쿠폰과 같은 3,000원 할인
      coupon: account && body.couponId ? { couponId: body.couponId, benefitType: 'fixed_discount', discountAmount: 3000, discountPercent: null, maxDiscountAmount: null, serviceName: null } : null,
      courseRootId: body.courseRootId ?? null,
      fromRecommendation: false,
      visitAmount: null,
      visitedAt: null,
      refundAmount: null,
      pendingStartAt: null,
      pendingPartySize: null,
    };
    customerReservations.set(reservation.id, { ...reservation, accountId: account?.id ?? null, guestPhone: account ? null : guest.phone });
    return HttpResponse.json(reservation, { status: 201 });
  }),

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
