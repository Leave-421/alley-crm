import { ALLEYS } from './storeCatalog.js';
import skyBackground from './assets/bg-sky.webp';
import floorTexture from './assets/bg-floor.webp';
import endFrontGate from './assets/bg-end-front_gate.webp';
import endCenter from './assets/bg-end-center.webp';
import endBackStreet from './assets/bg-end-back_street.webp';
import propLamp from './assets/prop-lamp.webp';

/**
 * 그리는 층 전용 이미지(ARCHITECTURE 「3D 골목 보드」). 데이터 층(storeCatalog.js)은 이 파일을 모른다.
 * 가게 정면 · 장식 건물 · 하늘 · 바닥 · 골목 끝은 확정 목업(GuestAlley*.dc.html) 이미지, 채움 건물 일부와
 * 보도 소품은 골목 에셋(alley-assets) 이미지다.
 *
 * 목업 가게 15곳과 시드 가게 15곳은 업종 구성이 같아서(음식점 4 · 카페 3 · 주점 2 · 공방 2 · 나머지 1씩),
 * 같은 업종의 목업 사진을 골목 순서 · mapX 순서대로 한 장씩 짝지어 쓴다. 사진 속 간판에는 목업의 가상
 * 가게 이름이 박혀 있으므로, 간판 자리(signs)를 사진에서 재 두고 그 위에 실제 가게 이름을 덮어 쓴다.
 */

const IMAGE_FILES = import.meta.glob(['./assets/facade-*.webp', './assets/deco-*.webp', './assets/filler-*.webp'], {
  eager: true,
  import: 'default',
});

/**
 * 건물 그림 정보(원본 px). opaque: [불투명한 벽의 왼쪽 끝 열, 오른쪽 끝 열, 땅에 닿는 행, 지붕선 행(생략하면 0)] —
 * 그림 양옆 · 아래의 투명 여백을 잘라 벽이 칸 폭에 꽉 차고 땅에 붙게 하고(여백이 남으면 건물 사이가 비어 보인다),
 * 지붕선 위(물탱크 · 옥탑 · 풀숲)는 잘라낸다. fill: 벽이 칸 폭 전체를 채우기 시작하는 행 — 그 아래 건물 뒤를
 * 어둡게 메워, 윗층이 가게 층보다 좁은 건물 옆 틈으로 하늘이 보이지 않게 한다.
 * signs: 사진 속 간판 자리 { x, y, w, h, color(바탕, 사진에서 잰 중앙값), ink(글자), serif, vertical, painted(벽에 쓴 글자),
 * kind(재질: lightbox 기본 · channel 도드라진 글자 · wood · metal · awning) }.
 * 연필깎는집 · 책갈피 서점 사진은 벽 끝의 작은 세로 간판까지 왼쪽을 잘라 간판을 아예 뺐다.
 */
const BUILDINGS = {
  'facade-bookstore-1': {
    w: 460, h: 780, opaque: [47, 456, 777], fill: 157,
    signs: [
      { x: 45, y: 437, w: 412, h: 90, color: '#f9ebd2', ink: '#2b2522' },
    ],
  },
  'facade-cafe-1': { w: 460, h: 780, opaque: [7, 450, 778, 196], fill: 196, signs: [{ x: 40, y: 432, w: 380, h: 60, color: '#d6cebc', ink: '#2a221d', serif: true, kind: 'channel' }] },
  'facade-cafe-2': { w: 460, h: 780, opaque: [24, 439, 777, 68], fill: 68, signs: [{ x: 30, y: 462, w: 400, h: 68, color: '#d39948', ink: '#3b2412', kind: 'channel' }] },
  'facade-cafe-3': { w: 460, h: 780, opaque: [7, 444, 778, 152], fill: 152, signs: [{ x: 20, y: 562, w: 420, h: 42, color: '#755134', ink: '#f4e8d8', kind: 'wood' }] },
  'facade-dessert-1': { w: 460, h: 780, opaque: [21, 437, 777], fill: 140, signs: [{ x: 40, y: 432, w: 395, h: 70, color: '#e3e2d2', ink: '#2a2422' }] },
  'facade-gift_shop-1': { w: 460, h: 780, opaque: [28, 432, 778, 172], fill: 172, signs: [{ x: 50, y: 517, w: 380, h: 60, color: '#cb9b4c', ink: '#3a2a1a', kind: 'channel' }] },
  'facade-pub-1': {
    w: 460, h: 780, opaque: [25, 455, 776, 145], fill: 145,
    signs: [
      { x: 58, y: 422, w: 390, h: 93, color: '#a03a3a', ink: '#ffffff', kind: 'channel' },
      { x: 28, y: 312, w: 22, h: 186, color: '#8a3a36', ink: '#ffffff', vertical: true },
    ],
  },
  'facade-pub-2': { w: 460, h: 780, opaque: [11, 448, 778, 155], fill: 163, signs: [{ x: 10, y: 448, w: 440, h: 72, color: '#2d2c2d', ink: '#e6d9be', serif: true, kind: 'metal' }] },
  'facade-restaurant-1': { w: 460, h: 780, opaque: [3, 454, 778], fill: 129, signs: [{ x: 80, y: 498, w: 300, h: 36, color: '#71343d', ink: '#ffffff', kind: 'awning' }] },
  'facade-restaurant-2': {
    w: 460, h: 780, opaque: [26, 458, 777], fill: 175,
    signs: [
      { x: 110, y: 458, w: 260, h: 76, color: '#b9ae9e', ink: '#2a2422', painted: true },
      { x: 5, y: 308, w: 45, h: 210, color: '#efedea', ink: '#2a2422', vertical: true },
    ],
  },
  'facade-restaurant-3': {
    w: 460, h: 780, opaque: [31, 458, 777, 182], fill: 256,
    signs: [
      { x: 60, y: 450, w: 340, h: 75, color: '#c9c9c6', ink: '#3b3330', painted: true },
      { x: 4, y: 320, w: 50, h: 192, color: '#ecebec', ink: '#b3202a', vertical: true },
    ],
  },
  'facade-restaurant-4': {
    w: 460, h: 780, opaque: [19, 442, 775], fill: 176,
    signs: [
      { x: 50, y: 448, w: 390, h: 85, color: '#d9dae2', ink: '#2a2626' },
      { x: 8, y: 362, w: 34, h: 136, color: '#ededef', ink: '#2a2626', vertical: true },
    ],
  },
  'facade-stationery-1': {
    w: 460, h: 780, opaque: [49, 458, 776, 208], fill: 208,
    signs: [
      { x: 110, y: 455, w: 290, h: 75, color: '#a2a19b', ink: '#2c2a28', painted: true },
    ],
  },
  'facade-workshop-1': {
    w: 460, h: 780, opaque: [25, 458, 776, 202], fill: 206,
    signs: [
      { x: 110, y: 455, w: 290, h: 75, color: '#a2a19b', ink: '#2c2a28', painted: true },
      { x: 4, y: 306, w: 42, h: 212, color: '#efe4d8', ink: '#2c2a28', vertical: true },
    ],
  },
  'facade-workshop-2': { w: 460, h: 780, opaque: [27, 434, 777], fill: 158, signs: [{ x: 60, y: 522, w: 370, h: 50, color: '#b9b4af', ink: '#3e3834', kind: 'channel' }] },
  'deco-1': { w: 460, h: 780, opaque: [1, 456, 778, 120], fill: 120 },
  'deco-2': { w: 460, h: 780, opaque: [4, 457, 777, 157], fill: 160, signs: [{ x: 120, y: 548, w: 220, h: 46, color: '#283541', ink: '#f1ede4', kind: 'channel' }] },
  'deco-3': { w: 460, h: 780, opaque: [9, 452, 778], fill: 138, signs: [{ x: 60, y: 412, w: 340, h: 66, color: '#3b281a', ink: '#f2ece0', kind: 'channel' }] },
  'deco-4': { w: 460, h: 780, opaque: [3, 455, 776, 180], fill: 180 },
  'deco-5': { w: 460, h: 780, opaque: [11, 442, 769], fill: 100, signs: [{ x: 40, y: 512, w: 380, h: 56, color: '#362a23', ink: '#efe5d6', kind: 'channel' }, { x: 12, y: 345, w: 34, h: 150, color: '#e9e6ea', ink: '#3a2a2a', vertical: true }] },
  'deco-6': { w: 460, h: 780, opaque: [15, 438, 762], fill: 159, signs: [{ x: 50, y: 425, w: 350, h: 85, color: '#31413f', ink: '#e9dfcc', kind: 'channel' }] },
  'deco-8': { w: 460, h: 780, opaque: [9, 450, 774], fill: 150, signs: [{ x: 75, y: 432, w: 310, h: 70, color: '#4c3c34', ink: '#e3d3b8', kind: 'wood' }] },
  'filler-front-cafe': { w: 303, h: 601, opaque: [9, 294, 597], fill: 2, signs: [{ x: 30, y: 256, w: 244, h: 42, color: '#262622', ink: '#f4f0e6', kind: 'channel' }] },
  // 일반 건물 옆벽(간판 없음)
  'filler-back-bookstore': { w: 285, h: 600, opaque: [1, 283, 598], fill: 0 },
  'filler-back-cafe': { w: 295, h: 600, opaque: [1, 293, 597], fill: 0 },
  'filler-back-dessert': { w: 307, h: 600, opaque: [0, 305, 598], fill: 0 },
  'filler-back-gift_shop': { w: 283, h: 600, opaque: [1, 272, 598], fill: 0 },
  'filler-back-pub': { w: 270, h: 600, opaque: [1, 268, 598], fill: 0 },
  'filler-back-restaurant': { w: 304, h: 601, opaque: [1, 301, 598], fill: 0 },
  'filler-back-stationery': { w: 331, h: 600, opaque: [2, 322, 598], fill: 0 },
  'filler-back-workshop': { w: 279, h: 604, opaque: [1, 277, 601], fill: 0 },
  'filler-front-dessert': { w: 344, h: 600, opaque: [10, 333, 598], fill: 2, signs: [{ x: 132, y: 202, w: 76, h: 76, color: '#f3ece2', ink: '#7a4a2a', kind: 'round' }] },
  'filler-front-pub': { w: 375, h: 605, opaque: [12, 362, 601], fill: 2, signs: [{ x: 70, y: 238, w: 240, h: 68, color: '#2a1c1a', ink: '#ffcf7a', kind: 'neon' }] },
  'filler-front-restaurant': { w: 319, h: 601, opaque: [11, 306, 598], fill: 3, signs: [{ x: 80, y: 234, w: 158, h: 56, color: '#b49670', ink: '#2a1e16', kind: 'wood' }] },
};

// 에셋 건물은 2층이라, 칸 폭에 맞춰도 목업 3층 건물(높이 ≈ 417)보다 높아지지 않게 세로를 이만큼까지만 둔다
const ASSET_MAX_ASPECT = 400 / 230;

const building = (key) =>
  Object.freeze({
    key,
    src: IMAGE_FILES[`./assets/${key}.webp`],
    ...(key.startsWith('filler-') ? { maxAspect: ASSET_MAX_ASPECT } : null),
    ...BUILDINGS[key],
  });

/**
 * 건물 그림을 폭 width에 맞출 때의 배율 · 크기(투명 여백을 잘라 불투명한 벽이 폭에 꽉 차게).
 * maxAspect(높이/폭)가 있으면 그보다 높아지지 않게 세로만 줄인다 — 2층짜리 에셋 건물이 칸 폭에 맞추면
 * 3층 목업 건물보다 높아져 어색해서.
 */
export function buildingMetrics(building, width) {
  const [left, right, bottom, roof = 0] = building.opaque;
  const visible = bottom + 1 - roof;
  const scale = width / (right - left + 1);
  const scaleY = building.maxAspect ? Math.min(scale, (building.maxAspect * width) / visible) : scale;
  return { scale, scaleY, offsetX: -left * scale, offsetY: -roof * scaleY, height: visible * scaleY };
}

// 목업 사진마다 간판에 찍힌 가게 이름(facade-{업종}-{번호} 순서). 이름이 같은 가게에 그 사진을 먼저 준다
const PHOTO_SIGN_NAMES = {
  restaurant: ['청춘식당', '연남식당', '골목국수', '한끼부엌'],
  cafe: ['골목카페', '하루로스터리', '모닝글로우'],
  dessert: ['소랑디저트'],
  pub: ['밤결포차', '달빛와인바'],
  workshop: ['공방 오브', '온기공방'],
  bookstore: ['책방 사계'],
  gift_shop: ['작은서랍'],
  stationery: ['연필가게'],
};

const CATEGORY_FACADES = Object.freeze(
  Object.fromEntries(
    Object.entries(PHOTO_SIGN_NAMES).map(([category, names]) => [category, names.map((_, i) => building(`facade-${category}-${i + 1}`))]),
  ),
);

const normalizeName = (name) => (name ?? '').replace(/\s/g, '');

/*
 * 채움 건물(참여하지 않는 가게 · 일반 건물) 묶음. 요식업(음식점 · 카페 · 디저트 · 주점)은 골목마다 여러 곳 있어도 되지만,
 * 그 밖의 업종은 세 골목 전체에서 한 번만 나온다:
 * - 서점 · 문구점 · 공방 · 소품샵은 실제 가게 업종이라 채움 건물로는 아예 쓰지 않는다.
 * - 옷가게(deco-2)는 정문 앞, 꽃집(deco-6)은 중앙 골목에만 둔다.
 */
const FOOD_FILLERS = ['deco-3', 'deco-5', 'deco-8', 'filler-front-restaurant', 'filler-front-cafe', 'filler-front-dessert', 'filler-front-pub'];
const PLAIN_FILLERS = ['deco-1', 'deco-4']; // 간판 없는 일반 건물
const ONE_OFF_FILLER = { front_gate: 'deco-2', center: 'deco-6' };
// 뒷면 민벽(일반 건물 옆벽). 가까이 크게 보이면 비어 보여서, 넓은 화면의 골목 끝처럼 모자랄 때만 마지막에 쓴다
const BLANK_FILLERS = ['restaurant', 'cafe', 'dessert', 'pub', 'workshop', 'bookstore', 'gift_shop', 'stationery'].map(
  (category) => `filler-back-${category}`,
);

/**
 * 가게가 없는 벽에 세우는 채움 건물(골목 앞에서부터 차례로, 한 골목 안에서는 같은 그림이 두 번 나오지 않는다).
 * 요식업 건물과 일반 건물을 번갈아 앞쪽에 두고, 골목마다 시작 위치를 달리해 골목끼리 같은 순서로 보이지 않게 한다.
 * 다 쓰면(아주 넓은 화면) 처음부터 다시 쓴다.
 */
function fillersFor(alley, alleyIndex) {
  const food = FOOD_FILLERS.map((_, i) => FOOD_FILLERS[(i + alleyIndex * 2) % FOOD_FILLERS.length]);
  const others = [...(ONE_OFF_FILLER[alley] ? [ONE_OFF_FILLER[alley]] : []), ...PLAIN_FILLERS];
  const front = [];
  food.forEach((key, i) => {
    front.push(key);
    if (i % 2 === 0 && others.length) front.push(others.shift());
  });
  const blanks = BLANK_FILLERS.map((_, i) => BLANK_FILLERS[(i + alleyIndex * 3) % BLANK_FILLERS.length]);
  return [...front, ...others, ...blanks].map(building);
}

export const ALLEY_FILLERS = Object.freeze(Object.fromEntries(ALLEYS.map((alley, index) => [alley, fillersFor(alley, index)])));

export const ALLEY_END_IMAGES = Object.freeze({
  front_gate: endFrontGate,
  center: endCenter,
  back_street: endBackStreet,
});

export const SKY_BACKGROUND_IMAGE = skyBackground;
export const FLOOR_IMAGE = floorTexture;

/**
 * 보도 소품(골목 에셋). height: 땅에서 그림 아래까지가 아니라 그림 자체 높이(목업 좌표).
 * 가로등은 그림(등 머리)을 lift만큼 올리고 그 아래를 기둥(shaft)으로 땅까지 세운다(shaftX: 그림 폭 대비 기둥 위치).
 */
export const PROPS = Object.freeze({
  lamp: { src: propLamp, w: 195, h: 325, height: 160, lift: 170, shaftX: 0.656, shaftWidth: 6 },
});

export const PROP_TYPES = Object.freeze([
  { type: 'lamp', tall: true },
]);

/**
 * 채움 건물(참여하지 않는 가게) 간판에 쓸 가상 상호. 건물 그림마다 [정문 앞, 중앙, 뒷길] 골목 순서 후보이고,
 * 실제 가게 이름과 같거나 이미 쓴 이름이면 SPARE_NAMES에서 대신 고른다.
 */
const FILLER_NAMES = {
  'deco-2': ['마음옷장', '빈티지 하루', '계절 옷장'],
  'deco-3': ['일식 PASTA', '토마토 키친', '오늘 파스타'],
  'deco-5': ['맛자랑', '엄마손 밥상', '소담 식당'],
  'deco-6': ['플라워랩', '꽃담', '들꽃 상점'],
  'deco-8': ['비스트로 누림', '저녁 식탁', '작은 부엌'],
  'filler-front-restaurant': ['골목식당', '우동 한 그릇', '소바 정'],
  'filler-front-cafe': ['골목커피', '커피 한 모금', '브루 하우스'],
  'filler-front-dessert': ['달콤한입', '슈가 베이크', '마카롱 상점'],
  'filler-front-pub': ['한잔골목', '네온 한 잔', '밤 산책'],
};
const SPARE_NAMES = ['바람 상회', '한 뼘 상점', '동네 사진관', '모퉁이 철물', '다정 세탁소', '오후 꽃집', '별빛 안경', '초록 잡화'];

/**
 * `${골목}:${건물 key}` → 그 골목 채움 건물 간판의 상호. 세 골목 전체에서 상호가 절대 겹치지 않게
 * (실제 가게 이름 포함) 한 번씩만 나눠 준다. 한 골목 안에서는 같은 건물 그림이 한 번만 나오므로 키가 겹치지 않는다.
 */
export function buildFillerNames(stores) {
  const used = new Set(stores.map((store) => normalizeName(store.name)));
  const spares = SPARE_NAMES.filter((name) => !used.has(normalizeName(name)));
  const names = new Map();
  ALLEYS.forEach((alley, alleyIndex) => {
    for (const filler of ALLEY_FILLERS[alley]) {
      if (!filler.signs || names.has(`${alley}:${filler.key}`)) continue;
      const wanted = FILLER_NAMES[filler.key]?.[alleyIndex];
      let name = wanted && !used.has(normalizeName(wanted)) ? wanted : spares.shift();
      // 후보가 다 떨어져도 사진 속 원래 이름(다른 골목과 같을 수 있음)을 드러내지 않게 번호 붙은 이름을 만든다
      for (let n = 1; !name || used.has(normalizeName(name)); n += 1) name = `골목 상점 ${n}`;
      used.add(normalizeName(name));
      names.set(`${alley}:${filler.key}`, name);
    }
  });
  return names;
}

/**
 * 가게 ID → 정면 건물. 간판 이름이 같은 목업 사진이 있으면 그것을 먼저 쓰고(예: '골목 국수' ↔ '골목국수'),
 * 나머지는 같은 업종의 남은 사진을 골목 순서 · mapX 순서대로 나눠 쓴다. 사진이 모자라면 돌려 쓴다.
 */
export function buildFacadeImageMap(stores) {
  const ordered = [...stores].sort(
    (a, b) => ALLEYS.indexOf(a.alley) - ALLEYS.indexOf(b.alley) || (a.mapX ?? 0) - (b.mapX ?? 0),
  );
  const images = new Map();
  const taken = new Set();
  for (const store of ordered) {
    const index = (PHOTO_SIGN_NAMES[store.category] ?? []).findIndex((name) => normalizeName(name) === normalizeName(store.name));
    if (index >= 0 && !taken.has(`${store.category}${index}`)) {
      taken.add(`${store.category}${index}`);
      images.set(store.id, CATEGORY_FACADES[store.category][index]);
    }
  }
  const turn = {};
  for (const store of ordered) {
    if (images.has(store.id)) continue;
    const list = CATEGORY_FACADES[store.category] ?? [];
    if (list.length === 0) {
      images.set(store.id, building('filler-front-restaurant'));
      continue;
    }
    const free = list.findIndex((_, i) => !taken.has(`${store.category}${i}`));
    if (free >= 0) {
      taken.add(`${store.category}${free}`);
      images.set(store.id, list[free]);
    } else {
      const next = turn[store.category] ?? 0;
      turn[store.category] = next + 1;
      images.set(store.id, list[next % list.length]);
    }
  }
  return images;
}
