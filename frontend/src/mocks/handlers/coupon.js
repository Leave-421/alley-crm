import { http, HttpResponse } from 'msw/http';
import { errorResponse, rejectInvalidCsrf } from '../responses.js';
import { getMockAccount } from '../session.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';

// CONTRACTS_API.md 「3.2 쿠폰 행사 설정」(담당 E, E11 #23) · 「3.4 쿠폰 상세 · 다운로드」(E12)의 목.
// 로그인한 사장님의 가게 하나를 흉내 낸다. 역할 검사(401 · 403)는 로그인 계약(C2) 목이 생기면 더한다.
// 상태는 메모리에만 있어서 새로고침하면 처음(행사 없음)으로 돌아간다.

const MOCK_STORE_ID = '3';
const BENEFIT_TYPES = [
  { value: 'fixed_discount', label: '정액 할인' },
  { value: 'percent_discount', label: '정률 할인' },
  { value: 'service', label: '서비스 제공' },
];

let events = []; // 이 가게의 모든 행사(중단한 것 포함)
let nextId = 1;

const isInt = (value) => Number.isInteger(value);
const activeEvent = () => events.find((event) => event.stoppedAt === null) ?? null;

function toResponse(event) {
  const remainingQuantity = event.totalQuantity - event.issuedCount;
  return { ...event, remainingQuantity, benefitAvailable: event.stoppedAt === null && remainingQuantity >= 1 };
}

/** 계약의 「검사(어긋나면 400 invalid)」 그대로. 맞으면 저장할 값, 틀리면 null. */
function validate(body) {
  if (!body || typeof body !== 'object') return null;
  const type = body.benefitType;
  if (!BENEFIT_TYPES.some((item) => item.value === type)) return null;
  if (!isInt(body.totalQuantity) || body.totalQuantity < 1) return null;

  const validDays = body.validDays ?? 7;
  if (!isInt(validDays) || validDays < 1 || validDays > 30) return null;
  const conditions = body.conditions ?? null;
  if (conditions !== null && (typeof conditions !== 'string' || conditions.length > 200)) return null;

  const saved = {
    benefitType: type,
    discountAmount: null,
    discountPercent: null,
    maxDiscountAmount: null,
    serviceName: null,
    serviceValue: null,
    validDays,
    conditions,
    totalQuantity: body.totalQuantity,
  };

  if (type === 'fixed_discount') {
    if (!isInt(body.discountAmount) || body.discountAmount < 1) return null;
    saved.discountAmount = body.discountAmount;
  }
  if (type === 'percent_discount') {
    if (!isInt(body.discountPercent) || body.discountPercent < 1 || body.discountPercent > 50) return null;
    const max = body.maxDiscountAmount ?? null;
    if (max !== null && (!isInt(max) || max < 1)) return null;
    saved.discountPercent = body.discountPercent;
    saved.maxDiscountAmount = max;
  }
  if (type === 'service') {
    const name = typeof body.serviceName === 'string' ? body.serviceName : '';
    if (name.length < 1 || name.length > 50) return null;
    if (!isInt(body.serviceValue) || body.serviceValue < 1) return null;
    saved.serviceName = name;
    saved.serviceValue = body.serviceValue;
  }
  return saved;
}

// 3.4 쿠폰 상세 · 다운로드 목: 쿠폰 행사를 하는 손님 쪽 목 가게(추천 카드 '예약 혜택' 배지도 이 목록으로 정한다)
const DAY_MS = 86_400_000;
const REDOWNLOAD_WAIT_MS = 30 * DAY_MS;
const OFFER_EVENTS = Object.freeze({
  S02: { benefitType: 'fixed_discount', discountAmount: 1000, conditions: '5,000원 이상 예약 시' },
  S04: { benefitType: 'percent_discount', discountPercent: 10, maxDiscountAmount: 3000, conditions: null },
  S07: { benefitType: 'fixed_discount', discountAmount: 2000, conditions: '2인 이상 방문 시' },
  S10: { benefitType: 'service', serviceName: '포장 리본', conditions: null },
  S12: { benefitType: 'fixed_discount', discountAmount: 3000, conditions: '2인 이상 방문 시' },
  S14: { benefitType: 'service', serviceName: '감자튀김', conditions: '4인 이상 방문 시' },
});

/** 진행 중인 쿠폰 행사가 있는 목 가게인지(추천 카드 couponBenefit) */
export function hasMockCouponOffer(storeId) {
  return Object.hasOwn(OFFER_EVENTS, storeId);
}

function offerEvent(storeId) {
  const event = OFFER_EVENTS[storeId];
  if (!event) return null;
  return { eventId: `ev-${storeId}`, discountAmount: null, discountPercent: null, maxDiscountAmount: null, serviceName: null, validDays: 7, ...event };
}

const downloadedCoupons = []; // { accountId, couponId, eventId, storeId, status, downloadedAt, expiresAt, ...혜택 }

function couponStatusFor(account, storeId) {
  const event = offerEvent(storeId);
  if (!event) return { event: null, downloadStatus: 'unavailable', nextDownloadableAt: null, myCoupons: [] };
  if (!account) return { event, downloadStatus: 'login_required', nextDownloadableAt: null, myCoupons: [] };
  const mine = downloadedCoupons.filter((coupon) => coupon.accountId === account.id && coupon.storeId === storeId);
  const myCoupons = mine.filter((coupon) => coupon.status === 'downloaded' || coupon.status === 'applied').map(({ couponId, eventId, status, expiresAt }) => ({ couponId, eventId, status, expiresAt }));
  const sameEvent = mine.filter((coupon) => coupon.eventId === event.eventId);
  if (sameEvent.some((coupon) => coupon.status === 'applied')) return { event, downloadStatus: 'applied_unused', nextDownloadableAt: null, myCoupons };
  const latest = Math.max(...sameEvent.map((coupon) => Date.parse(coupon.downloadedAt)), -Infinity);
  if (latest + REDOWNLOAD_WAIT_MS > Date.now()) {
    return { event, downloadStatus: 'waiting', nextDownloadableAt: new Date(latest + REDOWNLOAD_WAIT_MS).toISOString(), myCoupons };
  }
  return { event, downloadStatus: 'available', nextDownloadableAt: null, myCoupons };
}

export const couponHandlers = [
  // GET /api/v1/coupon-offers/{storeId} (3.4) — 누구나(사장님 · 상인회 403), 기록하지 않음
  http.get('/api/v1/coupon-offers/:storeId', ({ params }) => {
    const account = getMockAccount();
    if (account && account.role !== 'customer' && account.role !== 'guest') return errorResponse(403, 'forbidden_role', '손님만 볼 수 있어요.');
    if (!MOCK_STORES.some((store) => store.id === params.storeId)) return errorResponse(404, 'not_found', '가게를 찾을 수 없어요.');
    const member = account?.role === 'customer' ? account : null;
    return HttpResponse.json({ storeId: params.storeId, ...couponStatusFor(member, params.storeId) });
  }),

  // POST /api/v1/coupons (3.4) — 회원만. 노출 ID에서 대상 가게를 정한다(목 노출 ID: ex-{displayId 앞 8자}-{가게ID})
  http.post('/api/v1/coupons', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;
    const account = getMockAccount();
    if (!account || account.role === 'guest') return errorResponse(401, 'unauthorized', '로그인이 필요해요.');
    if (account.role !== 'customer') return errorResponse(403, 'forbidden_role', '회원 손님만 받을 수 있어요.');
    const body = await info.request.json().catch(() => null);
    if (typeof body?.exposureId !== 'string' || !body.exposureId) return errorResponse(400, 'invalid', '추천 정보를 확인해 주세요.');
    const storeId = /^ex-[0-9a-f]{8}-(S\d+)$/i.exec(body.exposureId)?.[1];
    if (!storeId || !MOCK_STORES.some((store) => store.id === storeId)) return errorResponse(404, 'not_found', '추천 기록을 찾을 수 없어요.');
    const { event, downloadStatus } = couponStatusFor(account, storeId);
    if (downloadStatus !== 'available') return errorResponse(409, 'conflict', '지금은 이 쿠폰을 받을 수 없어요.');
    const now = Date.now();
    const coupon = {
      couponId: `cp-${storeId}-${downloadedCoupons.length + 1}`,
      eventId: event.eventId,
      storeId,
      status: 'downloaded',
      benefitType: event.benefitType,
      discountAmount: event.discountAmount,
      discountPercent: event.discountPercent,
      maxDiscountAmount: event.maxDiscountAmount,
      serviceName: event.serviceName,
      conditions: event.conditions,
      downloadedAt: new Date(now).toISOString(),
      expiresAt: new Date(now + event.validDays * DAY_MS).toISOString(),
    };
    downloadedCoupons.push({ ...coupon, accountId: account.id });
    return HttpResponse.json(coupon, { status: 201 });
  }),


  http.get('/api/v1/coupon-events/benefit-types', () => HttpResponse.json({ items: BENEFIT_TYPES })),

  http.get('/api/v1/coupon-events/mine', () => {
    const current = activeEvent();
    return HttpResponse.json({ current: current ? toResponse(current) : null });
  }),

  http.post('/api/v1/coupon-events', async (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;

    const body = await info.request.json().catch(() => null);
    const saved = validate(body);
    if (!saved) return errorResponse(400, 'invalid', '입력값을 확인해 주세요.');
    if (activeEvent()) return errorResponse(409, 'conflict', '진행 중인 쿠폰 행사가 이미 있어요.');

    const event = {
      id: String(nextId++),
      storeId: MOCK_STORE_ID,
      ...saved,
      issuedCount: 0,
      confirmedAt: new Date().toISOString(),
      stoppedAt: null,
    };
    events = [...events, event];
    return HttpResponse.json(toResponse(event), { status: 201 });
  }),

  http.post('/api/v1/coupon-events/:eventId/stop', (info) => {
    const csrf = rejectInvalidCsrf(info);
    if (csrf) return csrf;

    const event = events.find((item) => item.id === info.params.eventId);
    if (!event) return errorResponse(404, 'not_found', '쿠폰 행사를 찾을 수 없어요.');
    if (event.stoppedAt !== null) return errorResponse(409, 'conflict', '이미 중단한 행사예요.');

    event.stoppedAt = new Date().toISOString();
    return HttpResponse.json(toResponse(event));
  }),

  // GET /api/v1/coupons/usable?storeId= (3.5) — 회원 손님만. 목: 어느 가게든 받아 둔 3,000원 할인 쿠폰 한 장
  http.get('/api/v1/coupons/usable', ({ request }) => {
    const account = getMockAccount();
    if (!account) return errorResponse(401, 'unauthorized', '로그인이 필요해요.');
    if (account.role !== 'customer') return errorResponse(403, 'forbidden_role', '회원 손님만 쓸 수 있어요.');
    const storeId = new URL(request.url).searchParams.get('storeId');
    if (!storeId) return errorResponse(400, 'invalid', '가게를 확인해 주세요.');
    const downloadedAt = new Date(Date.now() - 2 * 86_400_000).toISOString();
    const expiresAt = new Date(Date.now() + 5 * 86_400_000).toISOString();
    return HttpResponse.json({
      items: [
        {
          couponId: `cp-${storeId}`,
          eventId: `ev-${storeId}`,
          storeId,
          status: 'downloaded',
          benefitType: 'fixed_discount',
          discountAmount: 3000,
          discountPercent: null,
          maxDiscountAmount: null,
          serviceName: null,
          conditions: null,
          downloadedAt,
          expiresAt,
        },
      ],
    });
  }),
];
