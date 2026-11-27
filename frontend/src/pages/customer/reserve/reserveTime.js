// 예약 화면의 날짜 · 시각 표시. 서버는 UTC로 주고받고, 날짜 · 요일 · 시각은 운영 시간대(Asia/Seoul) 기준이다(CONTRACTS_API D2 · D5).

const SERVICE_TIME_ZONE = 'Asia/Seoul';
const DAY_MS = 24 * 60 * 60 * 1000;

const dateKeyFormat = new Intl.DateTimeFormat('en-CA', { timeZone: SERVICE_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' });
const weekdayFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: SERVICE_TIME_ZONE, weekday: 'short' });
const dayFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: SERVICE_TIME_ZONE, day: 'numeric' });
const longDateFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: SERVICE_TIME_ZONE, month: 'long', day: 'numeric', weekday: 'long' });
const timeFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: SERVICE_TIME_ZONE, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

/** 운영 시간대 날짜 'YYYY-MM-DD'(available-slots의 date 값) */
export function serviceDateKey(ms) {
  return dateKeyFormat.format(ms);
}

/** 오늘부터 count일의 날짜 칩 { key, weekday, day } */
export function upcomingDays(count, nowMs = Date.now()) {
  return Array.from({ length: count }, (_, i) => {
    const ms = nowMs + i * DAY_MS;
    return { key: serviceDateKey(ms), weekday: weekdayFormat.format(ms), day: dayFormat.format(ms).replace('일', '') };
  });
}

/** 'YYYY-MM-DD' → '11월 28일 토요일' */
export function formatDateKey(key) {
  return longDateFormat.format(new Date(`${key}T12:00:00+09:00`));
}

/** UTC ISO 시각 → '18:30'(운영 시간대) */
export function formatSlotTime(iso) {
  return timeFormat.format(new Date(iso));
}
