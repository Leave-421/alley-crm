/**
 * 그리는 층의 배치 · 카메라 수학(ARCHITECTURE 「3D 골목 보드」). 확정 목업 GuestAlley*.dc.html의
 * 스크립트(camTf · zc · DMAX/STEP/FAR/FZ · layout · facs/signs/floors/wires)를 그대로 옮겼다.
 * 모든 값은 목업 좌표계(폭 390px) 기준이다 — 화면 크기 맞춤은 AlleyScroll이 프레임 전체를 비율로 키운다.
 * 데이터 층(useAlleyBoard)은 이 파일을 모르고, 이 파일도 가게가 어디서 왔는지 모른다.
 */

export const DESIGN_WIDTH = 390;
export const MOCKUP_SCENE_HEIGHT = 764; // 목업 프레임 844 - 시연 배너 24 - 머리말 56
export const HORIZON_Y = 400; // 목업 world top · perspective-origin
export const MOCKUP_HALF_WIDTH = 150; // 목업 길 반폭(건물 벽이 x = ±150)
export const STEP = 460; // 구역 하나(가게 두 칸)
const SLOT_GAP = 230;
const FAR = 530;
const FLOOR_NEAR = 595; // 넓은 화면의 바닥은 카메라 5px 앞까지 보인다(그보다 가까우면 뒤집혀 그려짐)
const FZ = 78.3;
const ZOOM_Y = -56;

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

/**
 * 장면 폭에 맞춘 길 반폭. 목업(폭 390)은 150 그대로. 넓은 화면에서는 가장 가까운 건물 벽이 늘 화면 끝까지
 * 닿도록 넓힌다 — 컬링(FAR) 때문에 가장 가까운 벽의 앞쪽 끝은 최대 300px 떨어져 있고, 거기서 벽은
 * 2 × 반폭만큼 퍼져 보이므로 반폭 ≥ 화면 반폭 / 2 이면 된다(10% 여유).
 */
export const streetHalfWidthFor = (sceneWidth) => Math.max(MOCKUP_HALF_WIDTH, (sceneWidth / 2 / 2) * 1.1);

/** 목업 zc(m) */
export const slotZ = (m) => -60 - SLOT_GAP * m;

/** 목업 DMAX(가게 5곳이면 980) */
export const maxDepthFor = (count) => (count > 0 ? 60 + SLOT_GAP * (count - 1) : 0);

/** 목업은 가게 5곳 → 구역 3개 */
export const zoneCountFor = (count) => Math.max(1, Math.ceil(count / 2));

export const currentZone = (depth, count) => clamp(Math.round(depth / STEP), 0, zoneCountFor(count) - 1);

/** mapX 순서의 가게 → 목업 layout(m = -1 … n). 가게는 왼쪽 · 오른쪽 번갈아, 반대쪽 벽은 장식 건물 */
export function layoutSlots(storesInAlley) {
  const n = storesInAlley.length;
  const slots = [];
  for (let m = -1; m <= n; m += 1) {
    const store = m >= 0 && m < n ? storesInAlley[m] : null;
    slots.push({ m, z: slotZ(m), L: store && m % 2 === 0 ? store : null, R: store && m % 2 !== 0 ? store : null });
  }
  return slots;
}

/** 목업 camTf: 카메라 위치(ax, ay, az) · 회전(th) → world transform */
function camTf(ax, ay, az, th) {
  const r = (th * Math.PI) / 180;
  const c = Math.cos(r);
  const s = Math.sin(r);
  const vx = -ax;
  const vy = -ay;
  const vz = 600 - az;
  const cx = vx * c - vz * s;
  const cz = vx * s + vz * c;
  return `translateZ(600px) rotateY(${th}deg) translate3d(${(-cx).toFixed(1)}px,${(-vy).toFixed(1)}px,${(-cz).toFixed(1)}px)`;
}

/**
 * 건물마다 벽선에서 안쪽으로 물러선 정도(목업 px). 실제 골목처럼 건물 앞면이 조금씩 들쭉날쭉해야 걸어 들어갈 때
 * 모서리 · 옆벽이 보여 입체감이 난다. 칸 · 쪽마다 고정값이라 새로고침해도 같다.
 */
const SETBACKS = [0, 18, 6, 26, 10, 0, 22, 14];
export const setbackFor = (m, side) => SETBACKS[(((m + 1 + (side === 'R' ? 3 : 0)) % SETBACKS.length) + SETBACKS.length) % SETBACKS.length];

const BACK_WALL_SETBACK = 32; // 가장 깊은 물러섬(26)보다 뒤
const PROP_NEAR = 560; // 소품은 얇은 판이라 카메라 40px 앞까지 보여도 된다
export const SIDEWALK_RATIO = 0.197; // 목업 바닥 그림(폭 600)에서 한쪽 보도 벽돌 폭 118

/** 스크롤 깊이로 걸어 들어가기, 가게를 고르면 그 벽 쪽으로 돌아서 정면을 본다(목업 zoom) */
export function worldTransform({ depth, slots, selectedStoreId, halfWidth = MOCKUP_HALF_WIDTH }) {
  const selected = selectedStoreId && slots.find((slot) => slot.L?.id === selectedStoreId || slot.R?.id === selectedStoreId);
  if (selected) {
    const side = selected.L?.id === selectedStoreId ? 'L' : 'R';
    // 물러선 건물이면 그만큼 더 떨어져 서서 목업과 같은 크기로 정면을 본다
    const tz = FZ + halfWidth + setbackFor(selected.m, side);
    return side === 'L' ? camTf(selected.z, ZOOM_Y, tz, -90) : camTf(-selected.z, ZOOM_Y, tz, 90);
  }
  return camTf(0, 0, depth, 0);
}

/**
 * 바닥이 놓일 x 범위와 바닥 그림 위치. 바닥 그림은 늘 길 폭 전체 크기로 깔아서 보도 · 차도 폭이 걸을 때와
 * 확대했을 때 같다. 가게를 골라 벽 쪽으로 돌아섰을 때는 그 벽에서 카메라 바로 앞(515)까지만 깐다 —
 * 카메라는 벽에서 521.7 떨어져 서므로, 그보다 길게 깔면 바닥이 카메라 뒤까지 걸쳐 3D가 깨져 그려지고,
 * 짧게 깔면 화면 아래에 바닥이 끊긴 경계가 보인다.
 */
const ZOOM_FLOOR_SPAN = 515;

function groundBoxFor(halfWidth, selectedSide) {
  const full = halfWidth * 2;
  const span = Math.min(full, ZOOM_FLOOR_SPAN);
  const backgroundSize = `${full}px 100%`;
  if (selectedSide === 'L') return { left: -halfWidth, width: span, backgroundSize, backgroundPosition: 'left top' };
  if (selectedSide === 'R') return { left: halfWidth - span, width: span, backgroundSize, backgroundPosition: 'right top' };
  return { left: -halfWidth, width: full, backgroundSize, backgroundPosition: 'left top' };
}

/** 목업 floors · facs · signs · end(전깃줄은 뺐다). halfWidth가 150이 아니면 바닥을 그만큼 넓힌다 */
export function sceneLayers({ depth, slots, selectedStoreId = null, halfWidth = MOCKUP_HALF_WIDTH }) {
  const wide = halfWidth > MOCKUP_HALF_WIDTH;
  const selectedSlot = selectedStoreId && slots.find((slot) => slot.L?.id === selectedStoreId || slot.R?.id === selectedStoreId);
  const zoom = Boolean(selectedSlot);
  const selectedSide = selectedSlot ? (selectedSlot.L?.id === selectedStoreId ? 'L' : 'R') : null;
  const floors = [];
  const facades = [];
  const returns = [];
  const signs = [];
  const nearFacades = { L: null, R: null };
  let fillerCount = 0;
  // 넓은 화면은 도크가 길 앞쪽을 다 가리지 않으니, 맨 앞에 바닥을 한 칸 더 깔고 카메라 바로 앞까지 보여서
  // 길 아래 회색 띠가 드러나지 않게 한다(폰은 목업 그대로)
  if (wide) {
    const z = slotZ(-2);
    floors.push({ key: 'floor-2', transform: `translate3d(0px,140px,${z}px) rotateX(90deg)`, visible: zoom || z + 115 + depth <= FLOOR_NEAR });
  }
  for (const slot of slots) {
    const { z } = slot;
    const visible = zoom || z + 115 + depth <= FAR;
    const floorVisible = wide ? zoom || z + 115 + depth <= FLOOR_NEAR : visible;
    floors.push({ key: `floor${slot.m}`, z, transform: `translate3d(0px,140px,${z}px) rotateX(90deg)`, visible: floorVisible });
    for (const side of ['L', 'R']) {
      const store = slot[side];
      const sign = side === 'L' ? -1 : 1;
      const dx = setbackFor(slot.m, side);
      const x = sign * (halfWidth + dx);
      const rot = side === 'L' ? 90 : -90;
      const facadeVisible = selectedSide ? side === selectedSide : visible;
      const facade = {
        key: `${side}${slot.m}`,
        side,
        store,
        // 가게 없는 벽은 골목 앞에서부터 채움 건물을 한 장씩 차례로 쓴다(한 골목 안에서 같은 건물이 두 번 나오지 않게)
        fillerIndex: store ? null : fillerCount++,
        transform: `translate3d(${x}px,-55px,${z}px) rotateY(${rot}deg)`,
        // 벽 쪽으로 돌아섰을 때 반대편 벽은 목업에서도 뒷면이라 안 보인다. 길이 넓으면 그 벽이 카메라 뒤로
        // 가서 브라우저가 뒤집어 크게 그려 버리므로 아예 숨긴다
        visible: facadeVisible,
      };
      // 바로 앞(카메라 쪽) 건물과 물러선 정도가 다르면, 둘 사이 경계에 튀어나온 쪽 건물의 옆벽을 세운다 —
      // 걸어 들어갈 때는 튀어나온 건물의 모서리가, 가게 정면을 볼 때는 건물 사이 틈이 그 옆벽으로 막힌다
      const near = nearFacades[side];
      const nearDx = setbackFor(slot.m - 1, side);
      if (near && nearDx !== dx) {
        const farProtrudes = dx < nearDx;
        const owner = farProtrudes ? facade : near;
        // 옆벽 무늬는 튀어나온 건물 사진의 그 경계 쪽 끝 줄(왼쪽 벽은 사진 왼쪽이 카메라 쪽)
        const nearEdgeStrip = side === 'L' ? 'left' : 'right';
        const farEdgeStrip = side === 'L' ? 'right' : 'left';
        returns.push({
          key: `ret${side}${slot.m}`,
          facade: owner,
          strip: farProtrudes ? nearEdgeStrip : farEdgeStrip,
          width: Math.abs(nearDx - dx),
          transform: `translate3d(${side === 'L' ? -(halfWidth + Math.max(dx, nearDx)) : halfWidth + Math.min(dx, nearDx)}px,-55px,${z + 115}px)`,
          visible: owner.visible,
        });
      }
      nearFacades[side] = facade;
      facades.push(facade);
      if (store) {
        const sz = z + 95;
        signs.push({
          store,
          side,
          transform: `translate3d(${sign * (halfWidth + dx - 54)}px,-112px,${sz}px)`,
          visible: !zoom && sz + depth <= FAR,
        });
      }
    }
  }
  const endZ = slotZ(slots.length - 2) - 115;
  // 넓은 화면은 길이 골목 끝 그림(폭 300)보다 넓어 양옆이 빈다 — 그 자리를 장식 건물 정면(폭 230)으로
  // 길 끝까지 채워서, 골목 끝이 길 폭 전체에 걸친 한 줄의 건물 + 가운데 학교 사진이 되게 한다
  const endFill = [];
  for (let k = 0; 150 + 230 * k < halfWidth; k += 1) {
    const x = 150 + 115 + 230 * k;
    endFill.push({ key: `endL${k}`, fillerIndex: fillerCount++, transform: `translate3d(${-x}px,-55px,${endZ}px)` });
    endFill.push({ key: `endR${k}`, fillerIndex: fillerCount++, transform: `translate3d(${x}px,-55px,${endZ}px)` });
  }
  return {
    floors,
    facades,
    returns,
    signs,
    endFill,
    // 물러선 건물 앞 땅(벽선 바깥 40)도 보도 벽돌로 깐다. 벽 쪽으로 돌아섰을 때는 그 벽 쪽만(반대쪽은 카메라 뒤)
    floorEdges: ['L', 'R'].filter((side) => !selectedSide || side === selectedSide),
    // 건물 줄 뒤에 세우는 어두운 벽(가장 깊이 물러선 건물보다 뒤, 가장 낮은 건물보다 낮게). 건물 사이 틈으로
    // 하늘 대신 그늘진 벽이 보이게 한다
    backWalls: ['L', 'R']
      .filter((side) => !selectedSide || side === selectedSide)
      .map((side) => {
        const near = slotZ(-1) + 115;
        const length = near - endZ;
        const x = (side === 'L' ? -1 : 1) * (halfWidth + BACK_WALL_SETBACK);
        return {
          side,
          length,
          transform: `translate3d(${x}px,-55px,${(near + endZ) / 2}px) rotateY(${side === 'L' ? 90 : -90}deg)`,
        };
      }),
    groundBox: groundBoxFor(halfWidth, selectedSide),
    // 골목 끝 그림은 목업 크기(건물 높이와 같은 390) 그대로 둔다. 길 폭만큼 키우면 건물보다 훨씬 커 보인다
    endBox: { left: -150, top: -195, width: 300, height: 390 },
    // 넓은 화면은 양옆 건물과 이어지도록 하늘 쪽 윗부분만 흐리게(폰은 목업 그대로)
    endFade: wide,
    endTransform: `translate3d(0px,-55px,${endZ}px)`,
    endVisible: !zoom,
  };
}

/** 문자열 → 32비트 시드 */
function hashSeed(text) {
  let h = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/** 시드 고정 난수(mulberry32) — 같은 골목은 새로고침해도 같은 배치 */
function seededRandom(seedText) {
  let a = hashSeed(seedText);
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * 양쪽 보도에 소품을 시드 고정으로 흩어 놓는다. 앞뒤 간격을 두어 겹치지 않게 하고, 키 큰 것(tall)끼리는 더 띄운다.
 * types: [{ type, tall }] 중에서 고른다. 돌려주는 값은 위치(z · 쪽)와 좌우 뒤집기뿐이고, 크기 · 그림은 그리는 쪽이 정한다.
 */
export function propPlacements({ seedText, count, types }) {
  const random = seededRandom(seedText);
  // 첫 가게 칸부터 둔다 — 그보다 앞이면 카메라 바로 앞이라 크게 보여 골목을 가린다
  const start = slotZ(0) + 20;
  const end = slotZ(count) + 40;
  const placements = [];
  for (const side of ['L', 'R']) {
    let z = start - random() * 80;
    let lastTall = Infinity;
    while (z > end) {
      const pick = types[Math.floor(random() * types.length)];
      const usable = pick.tall && lastTall - z < 420 ? types.find((t) => !t.tall) : pick;
      placements.push({ key: `${side}${placements.length}`, type: usable.type, side, z: Math.round(z), mirror: random() < 0.5 });
      if (usable.tall) lastTall = z;
      z -= 150 + random() * 130;
    }
  }
  return placements;
}

/** 소품이 지금 보이는지: 벽 쪽으로 돌아섰을 때는 숨기고(옆에서 보면 얇은 선), 카메라 바로 앞은 넘기지 않는다 */
export const propVisible = (placement, depth, zoom) => !zoom && placement.z + depth <= PROP_NEAR;

/** 목업 dockShops: 지금 구역에서 가까운 가게 3곳 */
export function nearbyStores(storesInAlley, zone) {
  return storesInAlley
    .map((store, m) => ({ store, m }))
    .sort((a, b) => Math.abs(a.m - zone * 2) - Math.abs(b.m - zone * 2) || a.m - b.m)
    .slice(0, 3)
    .map(({ store }) => store);
}
