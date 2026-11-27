// 상인회 쿠폰 기간 입력 ↔ 계약 시각. 화면은 날짜(운영 시간대 Asia/Seoul)로 받고,
// 계약은 [시작, 끝) UTC 시각이라 '끝 날짜'는 그다음 날 00:00(KST)으로 보낸다(그 날 하루를 포함).

const KST_OFFSET = '+09:00';

/** 'YYYY-MM-DD' 시작 날짜 → 그 날 00:00 KST의 UTC ISO */
export const startOfDay = (dateKey) => new Date(`${dateKey}T00:00:00${KST_OFFSET}`).toISOString();

/** 'YYYY-MM-DD' 끝 날짜(포함) → 다음 날 00:00 KST의 UTC ISO */
export function endOfDay(dateKey) {
  const date = new Date(`${dateKey}T00:00:00${KST_OFFSET}`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString();
}

const dayFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric' });

/** [시작, 끝) → '12월 1일 ~ 12월 7일'(끝은 그 전날까지 포함으로 보여 준다) */
export function formatPeriod(startIso, endIso) {
  const lastDay = new Date(Date.parse(endIso) - 1);
  return `${dayFormat.format(new Date(startIso))} ~ ${dayFormat.format(lastDay)}`;
}

/** 오늘(KST) 'YYYY-MM-DD' */
export const todayKey = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
