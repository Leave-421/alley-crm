import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { RECOMMENDATION_BADGE_LABELS, getCourseProposals, getRecommendations, recordCourseProposals } from '@/api/recommendations.js';
import { ALLEY_LABELS, CATEGORY_LABELS } from '@/map/storeCatalog.js';
import { formatDateKey, formatSlotTime, serviceDateKey } from './reserveTime.js';
import styles from './ReservePage.module.css';

/**
 * 추천 카드 묶음을 불러온다.
 * - 회원의 기준 가게 예약(G-02): 정식 코스 제안(E7)을 조회하고 그린 가게를 표시 기록해 가게마다 courseProposalId를 받는다
 *   — '다음 코스로 예약하기'가 그 ID를 달고 가야 소개 후보가 된다.
 * - 비회원(제한 세션이 없어 정식 제안을 못 봄) · 다음 코스 완료(G-03): 그 가게 기준 지도 추천(기록 없음).
 */
async function loadCards({ reservation, storeId, useProposals }) {
  if (useProposals) {
    const data = await getCourseProposals(reservation.id);
    const shown = data.items.map((card) => card.store.id);
    const recorded = shown.length > 0 ? await recordCourseProposals(reservation.id, shown) : { items: [] };
    const proposalIds = new Map(recorded.items.map((item) => [item.storeId, item.courseProposalId]));
    return { ...data, items: data.items.map((card) => ({ ...card, proposalId: proposalIds.get(card.store.id) ?? null })) };
  }
  return getRecommendations(storeId);
}

/**
 * 예약 신청 완료(G-02 · G-03 완료 화면). 상태 '신청', 비회원은 예약번호(FR-AUTH-03).
 * 기준 가게 예약이면 다음 코스 추천 3곳과 '다음 코스로 예약하기'(FR-RSV-11, FR-CRS-01),
 * 다음 코스 예약이면 이어서 볼 곳 3곳('…골목에서 보기')과 '내 예약 보기'(코스로 묶여 보임).
 */
export default function ReserveDone({ store, alley, reservation, isMember, isCourse = false }) {
  const [recommendation, setRecommendation] = useState(null); // { data } | { error }

  useEffect(() => {
    let active = true;
    loadCards({ reservation, storeId: store.id, useProposals: isMember && !isCourse })
      .then((data) => active && setRecommendation({ data }))
      .catch((error) => active && setRecommendation({ error }));
    return () => {
      active = false;
    };
  }, [reservation, store.id, isMember, isCourse]);

  const items = recommendation?.data?.items ?? [];
  const publicData = recommendation?.data?.publicData;

  return (
    <main className={styles.page}>
      <div className={styles.doneIcon} aria-hidden="true">
        ✓
      </div>
      <h1 className={`${styles.title} ${styles.doneTitle}`}>{isCourse ? '다음 코스로 예약했어요' : '예약을 신청했어요'}</h1>

      <div className={styles.layout}>
        <div>
          <section className={`${styles.card} ${styles.doneCard}`} aria-label="신청한 예약">
            <div className={styles.doneStore}>{store.name}</div>
            <div className={styles.doneRow}>
              <span>방문 날짜</span>
              <b className={styles.num}>{formatDateKey(serviceDateKey(Date.parse(reservation.startAt)))}</b>
            </div>
            <div className={styles.doneRow}>
              <span>예약 시간</span>
              <b className={styles.num}>{formatSlotTime(reservation.startAt)}</b>
            </div>
            <div className={styles.doneRow}>
              <span>인원</span>
              <b className={styles.num}>{reservation.partySize}명</b>
            </div>
            {reservation.depositAmount > 0 && (
              <div className={styles.doneRow}>
                <span>예약금</span>
                <b className={styles.num}>{reservation.depositAmount.toLocaleString('ko-KR')}원</b>
              </div>
            )}
            {reservation.coupon && (
              <div className={styles.doneRow}>
                <span>쿠폰</span>
                <b>적용</b>
              </div>
            )}
            {!isMember && (
              <div className={styles.reservationNo}>
                <span className={styles.cap}>예약번호 · 비회원 예약 조회에 써요</span>
                <b className={styles.reservationNoValue}>{reservation.reservationNo}</b>
              </div>
            )}
            <span className={`${styles.badge} ${styles.statusBadge}`}>신청 · 가게 확인 후 확정</span>
          </section>

          <div className={styles.info}>
            {isCourse
              ? '기준 가게 예약과 한 코스로 묶였어요. 내 예약에서 시간순으로 함께 볼 수 있어요.'
              : `${store.name} 예약을 신청해서 다음 코스 예약이 열렸어요. 같은 날 이어서 가기 좋은 3곳을 보여드려요.`}
          </div>
        </div>

        <section aria-labelledby="next-course">
          <h2 id="next-course" className={styles.sectionTitle}>
            {isCourse ? '이어서 볼 곳 3곳' : '다음 코스 추천 3곳'}
          </h2>
          {!recommendation && <p className={styles.notice}>추천을 불러오는 중이에요…</p>}
          {recommendation?.error && <p className={styles.notice}>추천을 불러오지 못했어요.</p>}
          {recommendation?.data && items.length === 0 && <p className={styles.notice}>지금은 추천할 가게가 없어요.</p>}
          {items.map((card) => (
            <article key={card.store.id} className={`${styles.card} ${styles.recCard}`}>
              <span className={styles.recRank}>{card.rank}</span>
              <div className={styles.recBody}>
                <div className={styles.recName}>
                  {card.store.name} <span className={styles.cap}>{CATEGORY_LABELS[card.store.category] ?? card.store.category}</span>
                </div>
                <div className={styles.recBadges}>
                  <span className={styles.badge}>{RECOMMENDATION_BADGE_LABELS[card.badge] ?? card.badge}</span>
                  {card.couponBenefit && <span className={`${styles.badge} ${styles.benefitBadge}`}>예약 혜택</span>}
                </div>
                <p className={styles.cap}>{card.reasonText}</p>
                <div className={styles.recActions}>
                  {!isCourse && (
                    <Link
                      to={`/reservations/${encodeURIComponent(reservation.id)}/next?store=${encodeURIComponent(card.store.id)}${card.proposalId ? `&proposal=${encodeURIComponent(card.proposalId)}` : ''}`}
                      state={{ rootStoreId: store.id, rootStartAt: reservation.startAt }}
                      className={`${buttonStyles.button} ${buttonStyles.primary} ${styles.recPrimary}`}
                    >
                      다음 코스로 예약하기
                    </Link>
                  )}
                  <Link to={`/alleys/${card.store.alley}`} className={styles.recLink}>
                    {ALLEY_LABELS[card.store.alley] ?? ''} 골목에서 보기
                  </Link>
                </div>
              </div>
            </article>
          ))}
          {items.some((card) => card.badge === 'local_popular') && (
            <p className={styles.cap}>
              '지역 인기 가게'는 방문 기록이 부족해 공공데이터로 추천한 가게예요.
              {publicData && ` 출처: ${publicData.source} · 기준: ${publicData.period}`}
            </p>
          )}

          <div className={styles.doneActions}>
            <Link to={isMember ? '/reservations' : '/guest-reservation'} className={`${buttonStyles.button} ${buttonStyles.secondary} ${styles.linkButton}`}>
              내 예약 보기
            </Link>
            <Link to={`/alleys/${alley}`} className={`${buttonStyles.button} ${styles.lightButton} ${styles.linkButton}`}>
              골목으로 돌아가기
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
