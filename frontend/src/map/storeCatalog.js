// 가게 데이터 — CONTRACTS_API.md 「가게 목록 · 위치(C6)」 Store 모양을 그대로 따른다.
// GET /api/v1/stores는 이미 merge됐지만 regionId를 손님 쪽에서 구할 방법이 아직 없어(권역 선택 없음, 손님 계정의
// regionId는 항상 null, 공개 권역 조회 API 없음 — 팀 결정 전까지는 지어내지 않는다) 실제 연결을 미루고,
// 시드(backend/src/main/resources/seed/region-stores.json)와 같은 값으로 mock을 둔다.
// 실제 GET /stores 연결은 regionId 확보 방법이 정해진 뒤 후속 이슈에서 한다.

/** CONTRACTS.md 「3」 store.category */
export const CATEGORY_LABELS = Object.freeze({
  restaurant: '음식점',
  cafe: '카페',
  dessert: '디저트',
  pub: '주점',
  workshop: '공방',
  bookstore: '서점',
  gift_shop: '소품샵',
  stationery: '문구점',
});

/** CONTRACTS.md 「3」 store.alley. 그리는 순서(정문 앞 → 중앙 → 뒷길)와 같다 */
export const ALLEY_LABELS = Object.freeze({
  front_gate: '정문 앞',
  center: '중앙',
  back_street: '뒷길',
});

export const ALLEYS = Object.freeze(Object.keys(ALLEY_LABELS));

/** CONTRACTS.md 「3」 store.closedDays 요일 값 */
export const WEEKDAY_LABELS = Object.freeze({
  mon: '월',
  tue: '화',
  wed: '수',
  thu: '목',
  fri: '금',
  sat: '토',
  sun: '일',
});

/** closedDays(요일 코드 목록) → 목업 문구('매주 월요일 휴무' · '매주 월·화 휴무' · '연중무휴') */
export function formatClosedDays(closedDays) {
  if (!closedDays || closedDays.length === 0) return '연중무휴';
  const days = closedDays.map((day) => WEEKDAY_LABELS[day] ?? day);
  return days.length === 1 ? `매주 ${days[0]}요일 휴무` : `매주 ${days.join('·')} 휴무`;
}

/** 영업시간 → 목업 문구('11:30 – 21:00'). 승인 전이라 값이 없으면 '-' */
export function formatHours(openTime, closeTime) {
  return openTime && closeTime ? `${openTime} – ${closeTime}` : '-';
}

const SEED_STORES = [
  { code: 'S01', name: '아침볕 커피', category: 'cafe', alley: 'front_gate', mapX: 30, mapY: 30, address: '정문 앞길 3', openTime: '08:00', closeTime: '22:00', closedDays: [] },
  { code: 'S02', name: '연필깎는집', category: 'stationery', alley: 'front_gate', mapX: 90, mapY: 50, address: '정문 앞길 8', openTime: '09:00', closeTime: '20:00', closedDays: ['sun'] },
  { code: 'S03', name: '든든한 백반', category: 'restaurant', alley: 'front_gate', mapX: 150, mapY: 30, address: '정문 앞길 15', openTime: '11:00', closeTime: '21:00', closedDays: ['sun'] },
  { code: 'S04', name: '책갈피 서점', category: 'bookstore', alley: 'front_gate', mapX: 210, mapY: 50, address: '정문 앞길 20', openTime: '10:00', closeTime: '21:00', closedDays: ['mon'] },
  { code: 'S05', name: '모퉁이 로스터리', category: 'cafe', alley: 'front_gate', mapX: 270, mapY: 30, address: '정문 앞길 27', openTime: '09:00', closeTime: '21:00', closedDays: [] },
  { code: 'S06', name: '골목 국수', category: 'restaurant', alley: 'center', mapX: 30, mapY: 110, address: '중앙길 4', openTime: '11:00', closeTime: '21:30', closedDays: [] },
  { code: 'S07', name: '느린 오후', category: 'cafe', alley: 'center', mapX: 90, mapY: 130, address: '중앙길 9', openTime: '10:00', closeTime: '22:00', closedDays: [] },
  { code: 'S08', name: '구름 디저트', category: 'dessert', alley: 'center', mapX: 150, mapY: 110, address: '중앙길 14', openTime: '11:00', closeTime: '22:00', closedDays: [] },
  { code: 'S09', name: '화덕 한 판', category: 'restaurant', alley: 'center', mapX: 210, mapY: 130, address: '중앙길 21', openTime: '11:30', closeTime: '22:00', closedDays: ['mon'] },
  { code: 'S10', name: '작은 서랍', category: 'gift_shop', alley: 'center', mapX: 270, mapY: 110, address: '중앙길 26', openTime: '11:00', closeTime: '21:00', closedDays: ['tue'] },
  { code: 'S11', name: '달빛 포차', category: 'pub', alley: 'back_street', mapX: 30, mapY: 190, address: '뒷길 2', openTime: '17:00', closeTime: '23:30', closedDays: [] },
  { code: 'S12', name: '숯불 한 상', category: 'restaurant', alley: 'back_street', mapX: 90, mapY: 210, address: '뒷길 7', openTime: '16:00', closeTime: '23:00', closedDays: [] },
  { code: 'S13', name: '흙손 도예공방', category: 'workshop', alley: 'back_street', mapX: 150, mapY: 190, address: '뒷길 13', openTime: '13:00', closeTime: '21:00', closedDays: ['mon', 'tue'] },
  { code: 'S14', name: '골목 끝 맥주집', category: 'pub', alley: 'back_street', mapX: 210, mapY: 210, address: '뒷길 19', openTime: '18:00', closeTime: '23:30', closedDays: ['sun'] },
  { code: 'S15', name: '실과 바늘', category: 'workshop', alley: 'back_street', mapX: 270, mapY: 190, address: '뒷길 24', openTime: '12:00', closeTime: '20:00', closedDays: ['wed'] },
];

/** @type {ReadonlyArray<object>} CONTRACTS_API.md Store[] 모양의 mock. id는 시드 코드(S01 등)를 그대로 쓴다 */
export const MOCK_STORES = Object.freeze(
  SEED_STORES.map((s) =>
    Object.freeze({
      id: s.code,
      regionId: 'mock-region',
      name: s.name,
      category: s.category,
      alley: s.alley,
      mapX: s.mapX,
      mapY: s.mapY,
      realLat: null,
      realLng: null,
      address: s.address,
      openTime: s.openTime,
      closeTime: s.closeTime,
      closedDays: s.closedDays,
      businessStatus: 'open',
      reservationEnabled: true,
      reservable: true,
      joinStatus: 'approved',
    }),
  ),
);
