// 예약 화면의 날짜 · 시각 글자. 날짜 기준은 서비스 운영 시간대 Asia/Seoul(05-policy, 계약 D4 「날짜 목록의 날짜」).
// 날짜 값은 'YYYY-MM-DD' 문자열로만 다루고, 날짜 계산은 UTC 자정으로 바꿔서 한다(브라우저 시간대와 상관없게).

const TIME_ZONE = 'Asia/Seoul';
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const dateKeyFormat = new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' });
const timeFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: TIME_ZONE, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const dayLabelFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: 'UTC', month: 'long', day: 'numeric', weekday: 'long' });
const wonFormat = new Intl.NumberFormat('ko-KR');

/** 시각(ms 또는 ISO)의 운영 시간대 날짜 'YYYY-MM-DD' */
export function toDateKey(instant) {
  return dateKeyFormat.format(new Date(instant));
}

/** 'YYYY-MM-DD' 형식이고 실제 있는 날짜면 true */
export function isDateKey(value) {
  if (typeof value !== 'string' || !DATE_PATTERN.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().startsWith(value);
}

/** 날짜를 days만큼 옮긴다. */
export function addDays(dateKey, days) {
  const date = new Date(`${dateKey}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** '11월 27일 금요일' */
export function formatDayLabel(dateKey) {
  return dayLabelFormat.format(new Date(`${dateKey}T00:00:00Z`));
}

/** 운영 시간대 '19:30' */
export function formatTime(instant) {
  return timeFormat.format(new Date(instant));
}

/** '10,000원' */
export function formatWon(amount) {
  return `${wonFormat.format(amount)}원`;
}
