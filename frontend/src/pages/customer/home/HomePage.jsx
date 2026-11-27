import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { ALLEY_LABELS, MOCK_STORES } from '@/map/storeCatalog.js';
import neighborhoodPhoto from './assets/neighborhood.webp';
import styles from './HomePage.module.css';

/**
 * 동네 그림 위 골목 이름표 자리(그림 폭 · 높이 대비 %). 확정 목업 GuestHome의 값 그대로 —
 * 캠퍼스 뒤가 뒷길, 앞을 가로지르는 길이 중앙, 정문에서 아래로 뻗은 길이 정문 앞 골목이다.
 */
const ALLEY_PINS = Object.freeze([
  { alley: 'back_street', left: 62, top: 13 },
  { alley: 'center', left: 14, top: 52.7 },
  { alley: 'front_gate', left: 49.4, top: 80 },
]);

// 바로 가기(G-14 「이동」): 내 예약 → G-04, 내 쿠폰 → G-10 쿠폰함
const SHORTCUTS = Object.freeze([
  { to: '/reservations', label: '내 예약' },
  { to: '/my', label: '내 쿠폰' },
]);

/**
 * 손님 홈(G-14). 동네 그림에서 골목 이름을 누르면 그 골목 3D(G-01)로 바로 들어가고, '골목 고르기'(G-15)와
 * 바로 가기를 둔다. 머리말(LOCORI · 알림 종 · 프로필)과 하단 탭은 공통 레이아웃(AppLayout)이 그린다.
 * 목업의 검색은 v1 제외(FR-MAP-03), 오늘의 코스(여유)는 이 이슈 완료 조건 밖이라 두지 않는다.
 */
export default function HomePage() {
  // 참여 가게 수는 골목 화면과 같은 가게 목록에서 센다(실제 가게 API로 바뀌면 같이 바뀜)
  const storeCount = MOCK_STORES.length;
  return (
    <main className={styles.page}>
      <h1 className={styles.greeting}>오늘은 어느 골목에 갈까요?</h1>

      <div className={styles.columns}>
        <section className={styles.card} aria-labelledby="home-neighborhood">
          <span className={styles.badge}>참여 가게 {storeCount}곳</span>
          <h2 id="home-neighborhood" className={styles.cardTitle}>
            대학가 동네
          </h2>
          <p className={styles.lead}>밥 한 끼부터, 커피와 책 한 권까지.</p>

          <div className={styles.map}>
            <img
              src={neighborhoodPhoto}
              alt="벽돌 본관과 시계탑이 있는 대학교 캠퍼스를 가운데에 두고, 캠퍼스 뒤 뒷길 골목, 앞을 가로지르는 중앙 골목, 정면에서 아래로 뻗은 정문 앞 골목이 이어진 대학가 항공 사진"
              className={styles.mapImage}
            />
            {ALLEY_PINS.map((pin) => (
              <Link
                key={pin.alley}
                to={`/alleys/${pin.alley}`}
                className={styles.pin}
                style={{ left: `${pin.left}%`, top: `${pin.top}%` }}
                aria-label={`${ALLEY_LABELS[pin.alley]} 골목 3D로 보기`}
              >
                <span className={styles.pinLabel}>
                  <span className={styles.pinDot} aria-hidden="true" />
                  {ALLEY_LABELS[pin.alley]} 골목
                </span>
              </Link>
            ))}
          </div>
          <p className={styles.caption}>그림에서 골목 이름을 누르면 그 골목 3D로 바로 들어가요.</p>

          <Link to="/alleys" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.pick}`}>
            골목 고르기
          </Link>
        </section>

        <section className={styles.side} aria-labelledby="home-shortcuts">
          <h2 id="home-shortcuts" className={styles.sectionTitle}>
            바로 가기
          </h2>
          <div className={styles.list}>
            {SHORTCUTS.map((item) => (
              <Link key={item.to} to={item.to} className={styles.row}>
                <span className={styles.rowLabel}>{item.label}</span>
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                  className={styles.chevron}
                >
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </Link>
            ))}
          </div>
        </section>
      </div>
    </main>
  );
}
