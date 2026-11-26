// 소개 · 정산 화면 글자. 정산 달 · 날짜는 운영 시간대 Asia/Seoul 기준이다(D15 「정산 달」).

const TIME_ZONE = 'Asia/Seoul';
const monthKeyFormat = new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, year: 'numeric', month: '2-digit' });
const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: TIME_ZONE, month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const wonFormat = new Intl.NumberFormat('ko-KR');

/** 시각의 정산 달 'YYYY-MM' */
export const toMonthKey = (instant) => monthKeyFormat.format(new Date(instant)).slice(0, 7);

/** 달을 months만큼 옮긴다 */
export function addMonths(monthKey, months) {
  const [year, month] = monthKey.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1 + months, 1));
  return date.toISOString().slice(0, 7);
}

/** '2026년 11월' */
export function formatMonth(monthKey) {
  const [year, month] = monthKey.split('-').map(Number);
  return `${year}년 ${month}월`;
}

/** '11월 24일 20:20' */
export const formatDateTime = (instant) => dateTimeFormat.format(new Date(instant));

/** '1,200원', 음수는 '−1,200원' */
export function formatWon(amount) {
  return amount < 0 ? `−${wonFormat.format(-amount)}원` : `${wonFormat.format(amount)}원`;
}

/** 들어오고 나간 금액 '+2,000원' · '−1,680원' */
export const formatSignedWon = (amount) => (amount > 0 ? `+${formatWon(amount)}` : formatWon(amount));

/** 0.04 → '4%' */
export const formatRate = (rate) => `${Math.round(rate * 1000) / 10}%`;
