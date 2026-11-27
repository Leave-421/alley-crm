// 알림 종류(CONTRACTS.md 「3」 notification.type) → 화면 문구 · 배지 · 이동할 곳. 문구는 프론트가 정한다(C14).
// payload에는 ID와 몇 가지 값만 있고 가게 이름 · 손님 이름은 없다(FR-CUS-05) — 문구도 그 안에서 만든다.

const TIME = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const hhmm = (iso) => (iso ? TIME.format(new Date(iso)) : '');

/** 배지 색(목업): 기본 · 쿠폰 · 안내 */
export const BADGE_TONES = Object.freeze({ DEFAULT: 'default', COUPON: 'coupon', INFO: 'info' });

/** 손님(회원 · 비회원) 알림 — G-11 */
function customerText(type, payload) {
  switch (type) {
    case 'reservation_confirmed':
      return { badge: '확정', title: '예약이 확정됐어요.', to: '/reservations' };
    case 'change_approved':
      return { badge: '변경', title: '예약 변경 요청이 승인됐어요.', to: '/reservations' };
    case 'change_rejected':
      return { badge: '변경', title: '예약 변경 요청이 거절됐어요.', caption: '원래 예약은 그대로예요.', to: '/reservations' };
    case 'reservation_rejected': {
      // 기준 가게 예약이 거절돼도 다음 코스 예약은 그대로다(FR-RSV-12, FR-NTF-03 #2)
      const kept = payload?.nextCourseReservationIds?.length ?? 0;
      return {
        badge: '거절',
        title: '가게가 예약을 받지 못했어요.',
        caption: kept > 0 ? `이 예약에 이어 잡은 다음 코스 예약 ${kept}건은 그대로예요.` : null,
        to: '/reservations',
      };
    }
    case 'reservation_canceled':
      return { badge: '취소', title: '예약이 자동으로 취소됐어요.', caption: '방문 처리가 되지 않은 예약이에요.', to: '/reservations' };
    case 'reminder':
      return { badge: '리마인드', title: '30분 안에 방문할 예약이 있어요.', caption: '늦을 것 같으면 내 예약에서 알려 주세요.', to: '/reservations' };
    case 'next_store_recommended':
      return { badge: '추천', title: '다음에 들르기 좋은 가게를 추천해요.', to: '/alleys' };
    case 'coupon_status':
      return payload?.status === 'used'
        ? { badge: '쿠폰', tone: BADGE_TONES.COUPON, title: '쿠폰을 사용했어요.', to: '/my' }
        : { badge: '쿠폰', tone: BADGE_TONES.COUPON, title: '쿠폰 사용 기간이 끝났어요.', to: '/my' };
    case 'association_coupon_opened':
      return { badge: '쿠폰', tone: BADGE_TONES.COUPON, title: '상인회 쿠폰 행사가 열렸어요.', to: '/my' };
    case 'visit_amount_recorded':
      return { badge: '방문 금액', title: '방문 금액이 기록됐어요.', to: '/reservations' };
    default:
      return null;
  }
}

/** 사장님 알림 — O-10 */
function ownerText(type, payload) {
  switch (type) {
    case 'reservation_requested':
      return { badge: '새 예약', title: '새 예약 신청이 들어왔어요.', to: '/owner' };
    case 'change_requested':
      return { badge: '변경 요청', title: '손님이 예약 변경을 요청했어요.', to: '/owner' };
    case 'reservation_canceled':
      return { badge: '취소', title: '손님이 예약을 취소했어요.', to: '/owner' };
    case 'delay_reported':
      return {
        badge: '지연 안내',
        tone: BADGE_TONES.INFO,
        title: `${hhmm(payload?.startAt)} 예약 손님이 늦는다고 알렸어요 · 예상 도착 ${hhmm(payload?.expectedArrivalAt)}`,
        caption: '예약 시간은 그대로예요.',
        to: '/owner',
      };
    case 'store_join_result':
      return { badge: '가입', title: payload?.joinStatus === 'approved' ? '가게 가입이 승인됐어요.' : '가게 가입 결과가 나왔어요.', to: '/owner' };
    case 'visit_report_check_requested':
      return { badge: '신고 확인', tone: BADGE_TONES.INFO, title: '상인회가 방문 신고 확인을 요청했어요.', to: '/owner' };
    default:
      return null;
  }
}

/** 알림 한 건의 화면 문구. 모르는 종류는 일반 문구로 보여준다(새 종류가 추가돼도 목록이 깨지지 않게) */
export function describeNotification(notification, role) {
  const text = role === 'owner' ? ownerText(notification.type, notification.payload) : customerText(notification.type, notification.payload);
  return { tone: BADGE_TONES.DEFAULT, caption: null, to: null, ...(text ?? { badge: '알림', title: '새 알림이 있어요.' }) };
}

const DATE = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric' });
const DAY_KEY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' });

/** 목업처럼 '방금' · '오늘 17:40' · '어제 21:10' · '11월 24일' */
export function formatNotificationTime(iso, nowMs = Date.now()) {
  const ms = Date.parse(iso);
  if (nowMs - ms < 60_000) return '방금';
  const day = DAY_KEY.format(ms);
  if (day === DAY_KEY.format(nowMs)) return `오늘 ${hhmm(iso)}`;
  if (day === DAY_KEY.format(nowMs - 86_400_000)) return `어제 ${hhmm(iso)}`;
  return DATE.format(ms);
}
