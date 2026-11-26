import { STORE_ALLEY_LABELS, STORE_CATEGORY_LABELS, WEEKDAY_LABELS } from '@/api/stores.js';

// 가게 관리 화면의 글자. 값 목록은 CONTRACTS.md 「3」 store.*.

export const categoryLabel = (value) => STORE_CATEGORY_LABELS[value] ?? value;
export const alleyLabel = (value) => STORE_ALLEY_LABELS[value] ?? value;

/** '11:00 ~ 21:00 · 휴무 월' / 연중무휴 / 시간 없음 */
export function formatHours({ openTime, closeTime, closedDays }) {
  const hours = openTime && closeTime ? `${openTime} ~ ${closeTime}` : '영업시간 없음';
  const days = closedDays ?? [];
  const closed = days.length === 0 ? '연중무휴' : `휴무 ${Object.keys(WEEKDAY_LABELS).filter((day) => days.includes(day)).map((day) => WEEKDAY_LABELS[day]).join(' · ')}`;
  return `${hours} · ${closed}`;
}

/** 지도 좌표 '(30, 30)' 또는 '미지정' */
export function formatMapPoint({ mapX, mapY }) {
  return mapX === null || mapY === null ? '미지정' : `(${mapX}, ${mapY})`;
}
