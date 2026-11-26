import { STORE_ALLEY_LABELS, STORE_CATEGORY_LABELS, STORE_JOIN_RULES, WEEKDAY_LABELS } from '@/api/stores.js';

// O-11 가게 정보 입력 값 다루기. 신청 · 다시 신청이 같이 쓴다.

/** 빈 가게 정보(입력 칸 값) */
export const EMPTY_STORE_INFO = Object.freeze({ name: '', category: '', alley: '', openTime: '', closeTime: '', closedDays: [] });

/** StoreJoin 응답 → 입력 칸 값(다시 신청할 때 이전 값을 채운다) */
export function storeInfoFrom(join) {
  return {
    name: join.name ?? '',
    category: join.category ?? '',
    alley: join.alley ?? '',
    openTime: join.openTime ?? '',
    closeTime: join.closeTime ?? '',
    closedDays: join.closedDays ?? [],
  };
}

/**
 * 가게 정보 검사(계약 C5 StoreJoinInfo): 이름 1 ~ 50자, 업종 · 골목 값 목록 안, 영업 시작 < 종료.
 * 휴무일은 고르지 않으면 연중무휴(빈 목록). 맞으면 빈 객체.
 */
export function validateStoreInfo(info) {
  const errors = {};
  const name = info.name.trim();
  if (name.length < 1 || name.length > STORE_JOIN_RULES.nameMax) errors.name = `가게 이름을 1 ~ ${STORE_JOIN_RULES.nameMax}자로 넣어 주세요.`;
  if (!STORE_CATEGORY_LABELS[info.category]) errors.category = '업종을 골라 주세요.';
  if (!STORE_ALLEY_LABELS[info.alley]) errors.alley = '골목을 골라 주세요.';
  if (!info.openTime) errors.openTime = '영업 시작 시각을 넣어 주세요.';
  if (!info.closeTime) errors.closeTime = '영업 종료 시각을 넣어 주세요.';
  else if (info.openTime && info.openTime >= info.closeTime) errors.closeTime = '영업 종료는 시작보다 늦어야 해요.';
  return errors;
}

/** 입력 칸 값 → 요청 본문의 StoreJoinInfo(휴무일은 요일 순) */
export function toStoreInfoRequest(info) {
  const days = Object.keys(WEEKDAY_LABELS);
  return {
    name: info.name.trim(),
    category: info.category,
    alley: info.alley,
    openTime: info.openTime,
    closeTime: info.closeTime,
    closedDays: days.filter((day) => info.closedDays.includes(day)),
  };
}
