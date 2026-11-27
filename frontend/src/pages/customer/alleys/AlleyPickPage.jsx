import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import chip from '@/map/chip.module.css';
import { ALLEY_LABELS, ALLEYS, MOCK_STORES } from '@/map/storeCatalog.js';
import streetFrontGate from './assets/street-front_gate.webp';
import streetCenter from './assets/street-center.webp';
import streetBackStreet from './assets/street-back_street.webp';
import styles from './AlleyPickPage.module.css';

/**
 * 골목마다 카드에 쓰는 거리 사진(확정 목업 GuestAlleys)과 한 줄 소개. 소개는 목업 문구를 지금 가게 구성에 맞게 고쳤다
 * (목업은 가상 가게 기준이라 정문 앞에 '식사와 커피', 중앙에 '문구' 등 실제 가게와 다른 업종이 적혀 있었다).
 */
const ALLEY_CARDS = Object.freeze({
  front_gate: { photo: streetFrontGate, intro: '커피와 책, 든든한 한 끼가 있는 길' },
  center: { photo: streetCenter, intro: '국수와 화덕, 디저트와 소품이 모인 길' },
  back_street: { photo: streetBackStreet, intro: '저녁 한 잔과 숯불, 공방이 있는 길' },
});

/**
 * 골목 고르기(G-15). 한 권역(대학가 동네) 안의 골목 3개를 카드로 보여주고 '이 골목 들어가기'로 그 골목 3D(G-01)에 들어간다.
 * 골목은 권역 안의 표시 구역이라 권역을 고르는 화면이 아니다(FR-MAP-03).
 */
export default function AlleyPickPage() {
  return (
    <main className={styles.page}>
      <span className={styles.badge}>1단계 · 골목 고르기</span>
      <h1 className={styles.title}>대학가 동네</h1>
      <p className={styles.lead}>동네 안의 골목 중 하나를 골라요. 골목에서 스크롤로 골목 안을 걸으며 가게를 골라요.</p>

      <div className={styles.grid}>
        {ALLEYS.map((alley) => {
          // 골목 화면과 같은 순서(지도 왼쪽부터)로 가게 이름을 보여준다
          const inAlley = MOCK_STORES.filter((store) => store.alley === alley).sort((a, b) => (a.mapX ?? 0) - (b.mapX ?? 0));
          const label = ALLEY_LABELS[alley];
          return (
            <section key={alley} className={styles.card} aria-labelledby={`alley-${alley}`}>
              <img src={ALLEY_CARDS[alley].photo} alt={`${label} 골목 거리 사진`} className={styles.photo} />
              <div className={styles.head}>
                <div>
                  <h2 id={`alley-${alley}`} className={styles.name}>
                    {label} 골목
                  </h2>
                  <p className={styles.intro}>{ALLEY_CARDS[alley].intro}</p>
                </div>
                <span className={styles.badge}>가게 {inAlley.length}곳</span>
              </div>
              <ul className={styles.stores} aria-label={`${label} 골목 가게`}>
                {inAlley.map((store) => (
                  <li key={store.id} className={`${chip.chip} ${styles.store}`}>
                    {store.name}
                  </li>
                ))}
              </ul>
              <Link to={`/alleys/${alley}`} className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.enter}`}>
                이 골목 들어가기
              </Link>
            </section>
          );
        })}
      </div>
    </main>
  );
}
