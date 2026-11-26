import { buildingMetrics } from './alleyIllustrations.js';
import styles from './BuildingFace.module.css';

/** 간판 칸에 이름이 들어가도록 글자 크기(원본 px)를 정한다. 넘치면 두 줄 */
function signFont(name, box) {
  const chars = Math.max(1, [...name.replace(/\s/g, '')].length);
  if (box.vertical) return { size: Math.min(box.w * 0.7, (box.h * 0.86) / chars), lines: 1 };
  const single = (box.w * 0.84) / chars;
  if (single >= box.h * 0.32 || chars <= 4) return { size: Math.min(single, box.h * 0.66), lines: 1 };
  return { size: Math.min((box.w * 0.84) / Math.ceil(chars / 2), box.h * 0.38), lines: 2 };
}

/**
 * 건물 정면 한 장. 아래(땅)에 붙여 폭 width로 그리고, 가게면 사진 속 간판 자리에 실제 가게 이름을 덮어 쓴다 —
 * 골목 장면 · 도크 썸네일 · 시트 사진 어디서 보든 떠 있는 표지판과 같은 이름이 보이게.
 * 높이는 그림 비율대로라 칸(390)보다 조금 높을 수 있다(부모 아래에 붙여 위로 넘친다).
 */
export default function BuildingFace({ building, name, width, className, style }) {
  const { scale, scaleY, offsetX, offsetY, height } = buildingMetrics(building, width);
  const [left, right] = building.opaque;
  // 지붕선 위 · 벽 양옆 바깥(투명 여백, 벽 밖으로 튀어나온 간판)은 그리지 않는다
  const clip = `inset(${-offsetY}px ${(building.w - right - 1) * scale}px 0px ${left * scale}px)`;
  return (
    <div className={className ? `${styles.face} ${className}` : styles.face} style={{ ...style, width, height }}>
      {building.fill != null && <span className={styles.backing} style={{ top: Math.max(0, offsetY + building.fill * scaleY) }} />}
      <img
        src={building.src}
        alt=""
        draggable="false"
        className={styles.image}
        style={{ left: offsetX, top: offsetY, width: building.w * scale, height: building.h * scaleY, clipPath: clip }}
      />
      {name &&
        building.signs?.map((box) => {
          const font = signFont(name, box);
          const kind = box.painted ? 'painted' : (box.kind ?? 'lightbox');
          const classes = [styles.sign, styles[kind], box.vertical && styles.vertical, box.serif && styles.serif];
          return (
            <span
              key={`${box.x}-${box.y}`}
              className={classes.filter(Boolean).join(' ')}
              style={{
                left: offsetX + box.x * scale,
                top: offsetY + box.y * scaleY,
                width: box.w * scale,
                height: box.h * scaleY,
                backgroundColor: box.color,
                color: box.ink,
                fontSize: font.size * scale,
                lineHeight: font.lines === 2 ? 1.12 : 1,
              }}
            >
              {/* 세로 간판은 띄어쓰기 없이 한 줄로 내려 쓴다(빈칸이 자리를 먹어 글자가 잘린다) */}
              <span className={styles.text}>{box.vertical ? name.replace(/\s/g, '') : name}</span>
            </span>
          );
        })}
    </div>
  );
}
