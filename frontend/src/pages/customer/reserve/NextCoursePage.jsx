import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { MOCK_STORES } from '@/map/storeCatalog.js';
import { ReserveFlow } from './ReservePage.jsx';
import styles from './ReservePage.module.css';

/**
 * 다음 코스 예약(G-03). 기준 가게 예약 완료 화면(G-02)의 '다음 코스로 예약하기'에서 들어온다.
 * 주소: /reservations/:reservationId/next?store=추천가게ID&proposal=정식제안ID(없으면 생략)
 * 기준 예약의 가게 · 시각은 이동할 때 함께 넘긴 값(location.state)을 쓴다 — 기준 예약을 다시 읽는 API는 손님 종류마다 달라서.
 */
export default function NextCoursePage() {
  const { reservationId } = useParams();
  const [searchParams] = useSearchParams();
  const { state } = useLocation();
  const store = MOCK_STORES.find((item) => item.id === searchParams.get('store'));

  if (!store || !state?.rootStartAt || !state?.rootStoreId) {
    return (
      <main className={styles.page}>
        <section className={styles.card}>
          <p className={styles.notice}>기준 가게 예약 정보를 찾을 수 없어요. 예약 완료 화면에서 '다음 코스로 예약하기'를 다시 눌러 주세요.</p>
          <Link to="/reservations" className={`${buttonStyles.button} ${buttonStyles.secondary} ${styles.linkButton}`}>
            내 예약 보기
          </Link>
        </section>
      </main>
    );
  }

  return (
    <ReserveFlow
      store={store}
      alley={store.alley}
      course={{ rootReservationId: reservationId, rootStoreId: state.rootStoreId, rootStartAt: state.rootStartAt, proposalId: searchParams.get('proposal') }}
    />
  );
}
