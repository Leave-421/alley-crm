import { Fragment, useRef } from 'react';
import { CATEGORY_LABELS } from './storeCatalog.js';
import {
  ALLEY_END_IMAGES,
  ALLEY_FILLERS,
  FLOOR_IMAGE,
  PROPS,
  PROP_TYPES,
  SKY_BACKGROUND_IMAGE,
  buildingMetrics,
} from './alleyIllustrations.js';
import {
  DESIGN_WIDTH,
  HORIZON_Y,
  MOCKUP_SCENE_HEIGHT,
  SIDEWALK_RATIO,
  STEP,
  currentZone,
  layoutSlots,
  maxDepthFor,
  nearbyStores,
  propPlacements,
  propVisible,
  sceneLayers,
  streetHalfWidthFor,
  worldTransform,
  zoneCountFor,
} from './alleyCamera.js';
import BuildingFace from './BuildingFace.jsx';
import chip from './chip.module.css';
import styles from './AlleyCorridor.module.css';

const SKY_HEIGHT = 404; // 목업 하늘 그림 390 × 404
const FACADE_WIDTH = 230; // 목업 건물 칸 폭
const FACADE_BOTTOM = 195; // 건물 칸(.facade, top -195 · 높이 390) 안에서 땅 높이
const FLOOR_EDGE = 40; // 물러선 건물 앞에 더 까는 보도 폭(가장 깊은 물러섬 26보다 넉넉히)

/**
 * 그리는 층(ARCHITECTURE 「3D 골목 보드」). 확정 목업 GuestAlley*.dc.html의 스크롤 영역을 그대로 옮겼다:
 * 스크롤 컨테이너 안에 sticky 무대(하늘 · 바닥 · 전깃줄 · 양쪽 건물 · 떠 있는 간판 · 골목 끝)와
 * 안내 문구 · 아래 도크(구역 · 뒤로/더 들어가기 · 이 근처 가게 3곳). 값은 모두 목업 좌표계다.
 *
 * 화면 크기 맞춤: 3D 장면은 목업 높이(764) 캔버스에 그려서
 * - 장면이 더 높으면 캔버스를 그만큼 키워 꽉 채우고,
 * - 더 낮으면 아래(길 · 도크)를 기준으로 맞추고 위쪽 하늘을 잘라 도크가 가게를 가리지 않게 하고,
 * - 더 넓으면 길 폭을 넓혀 양쪽 건물이 화면 끝까지 이어지게 한다(폭 390이면 목업과 완전히 같음).
 * 안내 문구 · 도크는 목업 크기 그대로 둔다.
 */
export default function AlleyCorridor({
  alley,
  alleyLabel,
  storesInAlley,
  facadeImages,
  fillerNames,
  depth,
  duration,
  selectedStoreId,
  sceneWidth,
  sceneHeight,
  onDepthChange,
  onSelectStore,
}) {
  const scrollerRef = useRef(null);
  const zoom = selectedStoreId != null;
  const worldScale = Math.max(1, sceneHeight / MOCKUP_SCENE_HEIGHT);
  const canvasWidth = sceneWidth / worldScale;
  // 장면이 목업보다 낮으면 캔버스를 위로 삐져나오게 두지 않고(브라우저가 3D를 지평선에서 잘라 그림),
  // 캔버스 높이를 장면에 맞춘 뒤 지평선 · 배경을 그만큼 올려 위쪽 하늘만 잘라낸다
  const canvasHeight = sceneHeight / worldScale;
  const offsetY = canvasHeight - MOCKUP_SCENE_HEIGHT;
  const horizonY = HORIZON_Y + offsetY;
  const halfWidth = streetHalfWidthFor(canvasWidth);
  const skyHeight = (SKY_HEIGHT * canvasWidth) / DESIGN_WIDTH;
  const slots = layoutSlots(storesInAlley);
  const layers = sceneLayers({ depth, slots, selectedStoreId, halfWidth });
  const maxDepth = maxDepthFor(storesInAlley.length);
  const zoneCount = zoneCountFor(storesInAlley.length);
  const zone = currentZone(depth, storesInAlley.length);
  const fillers = ALLEY_FILLERS[alley] ?? ALLEY_FILLERS.front_gate;
  const buildingOf = (facade) => (facade.store ? facadeImages.get(facade.store.id) : fillers[facade.fillerIndex % fillers.length]);
  // 간판 이름: 가게는 실제 이름, 채움 건물은 세 골목 전체에서 겹치지 않게 나눠 준 가상 상호
  const nameOf = (facade) => facade.store?.name ?? fillerNames.get(`${alley}:${buildingOf(facade).key}`);
  const props = propPlacements({ seedText: `${alley}-props`, count: storesInAlley.length, types: PROP_TYPES });
  const sidewalk = SIDEWALK_RATIO * 2 * halfWidth;
  const worldStyle = {
    left: canvasWidth / 2,
    top: horizonY,
    transform: worldTransform({ depth, slots, selectedStoreId, halfWidth }),
    transitionDuration: duration,
  };

  function handleScroll(event) {
    const next = Math.max(0, Math.min(maxDepth, Math.round(event.currentTarget.scrollTop)));
    if (next !== depth) onDepthChange(next, '0.2s');
  }

  function goTo(target) {
    scrollerRef.current?.scrollTo({ top: target, behavior: 'auto' });
    onDepthChange(target, '0.45s');
  }

  return (
    <div ref={scrollerRef} className={styles.scroller} style={{ width: sceneWidth, height: sceneHeight }} onScroll={handleScroll}>
      <div className={styles.stage} style={{ height: sceneHeight }}>
        <div
          className={styles.canvas}
          style={{
            width: canvasWidth,
            height: canvasHeight,
            left: (sceneWidth - canvasWidth) / 2,
            transform: `scale(${worldScale})`,
            perspectiveOrigin: `50% ${horizonY}px`,
            // 지평선 아래는 먼 안개 색(넓은 화면에서 골목 끝 그림 양옆에 드러나는 곳). 가까운 땅은 바닥 그림이 덮는다
            backgroundImage: `url(${SKY_BACKGROUND_IMAGE}), linear-gradient(180deg, #96b0d2 0px, #eaecec 398px, #dfe1e0 100%)`,
            backgroundSize: `${canvasWidth}px ${skyHeight}px, 100% ${MOCKUP_SCENE_HEIGHT}px`,
            backgroundPosition: `center ${SKY_HEIGHT - skyHeight + offsetY}px, 0 ${offsetY}px`,
          }}
        >
          <div className={styles.world} style={worldStyle}>
            <img
              src={ALLEY_END_IMAGES[alley]}
              alt=""
              aria-hidden="true"
              className={layers.endFade ? `${styles.endWall} ${styles.endWallFade}` : styles.endWall}
              style={{ ...layers.endBox, transform: layers.endTransform, display: layers.endVisible ? 'block' : 'none' }}
            />
            {layers.endFill.map((fill) => (
              <div
                key={fill.key}
                aria-hidden="true"
                className={styles.facade}
                style={{ transform: fill.transform, display: layers.endVisible ? 'block' : 'none', pointerEvents: 'none' }}
              >
                <BuildingFace building={buildingOf(fill)} name={nameOf(fill)} width={FACADE_WIDTH} className={styles.facadeBody} />
              </div>
            ))}

            {layers.floors.map((floor) => (
              <Fragment key={floor.key}>
                <div
                  aria-hidden="true"
                  className={styles.floor}
                  style={{
                    ...layers.groundBox,
                    backgroundImage: `url(${FLOOR_IMAGE})`,
                    transform: floor.transform,
                    display: floor.visible ? 'block' : 'none',
                  }}
                />
                {layers.floorEdges.map((side) => (
                  <div
                    key={side}
                    aria-hidden="true"
                    className={styles.floorEdge}
                    style={{
                      left: side === 'L' ? -halfWidth - FLOOR_EDGE : halfWidth,
                      width: FLOOR_EDGE,
                      transform: floor.transform,
                      display: floor.visible ? 'block' : 'none',
                      backgroundImage: `url(${FLOOR_IMAGE})`,
                      backgroundSize: `${halfWidth * 2}px 100%`,
                      backgroundPosition: side === 'L' ? 'left top' : 'right top',
                    }}
                  />
                ))}
              </Fragment>
            ))}

            {props.map((placement) => {
              const prop = PROPS[placement.type];
              const width = (prop.height * prop.w) / prop.h;
              const total = prop.height + (prop.lift ?? 0);
              // 가로등은 보도 바깥쪽(차도 쪽), 나머지는 건물 벽에 붙여 둔다
              const inset = prop.lift ? sidewalk * 0.82 : Math.min(8 + width / 2, sidewalk - width / 2 - 4);
              const x = (placement.side === 'L' ? -1 : 1) * (halfWidth - inset);
              // 가로등은 팔이 길 쪽을 보게, 나머지는 시드대로 좌우를 뒤집는다
              const mirror = placement.type === 'lamp' ? placement.side === 'L' : placement.mirror;
              return (
                <div
                  key={placement.key}
                  aria-hidden="true"
                  className={styles.prop}
                  style={{
                    width,
                    height: total,
                    left: -width / 2,
                    top: -total,
                    transform: `translate3d(${x.toFixed(1)}px,140px,${placement.z}px)`,
                    display: propVisible(placement, depth, zoom) ? 'block' : 'none',
                  }}
                >
                  <span className={styles.propBody}>
                    <img
                      src={prop.src}
                      alt=""
                      decoding="async"
                      className={styles.propImage}
                      style={{ width, height: prop.height, transform: mirror ? 'scaleX(-1)' : undefined }}
                    />
                    {prop.lift && (
                      <span
                        className={styles.shaft}
                        style={{
                          left: (mirror ? 1 - prop.shaftX : prop.shaftX) * width - prop.shaftWidth / 2,
                          top: prop.height - 4,
                          width: prop.shaftWidth,
                          height: prop.lift + 4,
                        }}
                      />
                    )}
                  </span>
                </div>
              );
            })}

            {layers.backWalls.map((wall) => (
              <div
                key={`back${wall.side}`}
                aria-hidden="true"
                className={styles.backWall}
                style={{ left: -wall.length / 2, width: wall.length, transform: wall.transform }}
              />
            ))}

            {layers.returns.map((ret) => {
              const building = buildingOf(ret.facade);
              const metrics = buildingMetrics(building, FACADE_WIDTH);
              const [, right] = building.opaque;
              // 옆벽: 튀어나온 건물 정면 사진의 그 경계 쪽 끝 줄을 어둡게 이어 붙인다
              const bgX = ret.strip === 'left' ? metrics.offsetX : ret.width - (right + 1) * metrics.scale;
              return (
                <div
                  key={ret.key}
                  aria-hidden="true"
                  className={styles.ret}
                  style={{
                    top: FACADE_BOTTOM - metrics.height,
                    width: ret.width,
                    height: metrics.height,
                    transform: ret.transform,
                    display: ret.visible ? 'block' : 'none',
                    backgroundImage: `url(${building.src})`,
                    backgroundSize: `${building.w * metrics.scale}px ${building.h * metrics.scaleY}px`,
                    backgroundPosition: `${bgX}px ${metrics.offsetY}px`,
                  }}
                />
              );
            })}

            {layers.facades.map((facade) => (
              <div
                key={facade.key}
                className={styles.facade}
                style={{ transform: facade.transform, display: facade.visible ? 'block' : 'none' }}
              >
                <BuildingFace building={buildingOf(facade)} name={nameOf(facade)} width={FACADE_WIDTH} className={styles.facadeBody} />
                {facade.store && (
                  <button
                    type="button"
                    className={styles.facadeHit}
                    onClick={() => onSelectStore(facade.store.id)}
                    aria-label={`${facade.store.name} 크게 보기`}
                  />
                )}
              </div>
            ))}
          </div>

          {/* 떠 있는 간판은 같은 카메라의 별도 층에 그린다 — 장면(건물 · 가로등)보다 늘 위에 그려져서 가로등에 가리지 않는다 */}
          <div className={styles.world} style={worldStyle}>
            {layers.signs.map((sign) => (
              <div
                key={`sign-${sign.store.id}`}
                className={styles.sign}
                style={{ transform: sign.transform, display: sign.visible ? 'block' : 'none' }}
              >
                <span aria-hidden="true" className={styles.bracket} style={{ left: sign.side === 'L' ? -10 : 88, top: 9 }} />
                <span aria-hidden="true" className={styles.bracket} style={{ left: sign.side === 'L' ? -10 : 88, top: 32 }} />
                <button
                  type="button"
                  className={styles.signBoard}
                  onClick={() => onSelectStore(sign.store.id)}
                  aria-label={`${sign.store.name} 크게 보기`}
                >
                  <span className={styles.signName}>{sign.store.name}</span>
                  <span className={styles.signCat}>{CATEGORY_LABELS[sign.store.category] ?? sign.store.category}</span>
                </button>
              </div>
            ))}
          </div>
        </div>

        {!zoom && (
          <>
            <div className={styles.hint}>스크롤해서 골목 안으로 · 가게를 누르면 바로 확대돼요</div>
            <div className={styles.dock}>
              <div className={styles.dockHead}>
                <span className={styles.dockTitle}>
                  {alleyLabel} 골목 · <span className={styles.num}>구역 {zone + 1} / {zoneCount}</span>
                </span>
                <button
                  type="button"
                  className={chip.chip}
                  style={{ minHeight: 44, opacity: zone === 0 ? 0.4 : 1 }}
                  onClick={() => goTo(Math.max(0, (zone - 1) * STEP))}
                >
                  뒤로
                </button>
                <button
                  type="button"
                  className={`${chip.chip} ${chip.on}`}
                  style={{ minHeight: 44, opacity: zone === zoneCount - 1 ? 0.4 : 1 }}
                  onClick={() => goTo(Math.min(zoneCount - 1, zone + 1) * STEP)}
                >
                  더 들어가기
                </button>
              </div>
              <div className={styles.dockLabel}>이 근처 가게 3곳</div>
              <div className={styles.dockGrid}>
                {nearbyStores(storesInAlley, zone).map((store) => (
                  <button
                    key={store.id}
                    type="button"
                    className={styles.dockButton}
                    onClick={() => onSelectStore(store.id)}
                    aria-label={`${store.name} 크게 보기`}
                  >
                    <span className={styles.dockThumb}>
                      <BuildingFace building={facadeImages.get(store.id)} name={store.name} width={30} />
                    </span>
                    <span className={styles.dockText}>
                      <span className={styles.dockName}>{store.name}</span>
                      <span className={styles.dockCat}>{CATEGORY_LABELS[store.category] ?? store.category}</span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          </>
        )}
      </div>
      <div style={{ height: maxDepth }} aria-hidden="true" />
    </div>
  );
}
