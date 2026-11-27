import { useState } from 'react';
import styles from './PolicyPage.module.css';

/**
 * 정책 안내에 쓰는 기본값. 서버에서는 권역 설정값(NFR-04)이라 바뀔 수 있지만, 손님이 읽을 설정 조회 API가 없어
 * 안내 문구는 기획 문서의 기본값을 한 곳에 모아 쓴다.
 */
const POLICY_DEFAULTS = Object.freeze({
  guestRetentionMonths: 6, // 비회원 정보 보관: 마지막 방문 후(FR-CUS-07)
  noVisitRetentionDays: 30, // 방문 기록 없는 비회원: 마지막 예약 취소 후(FR-CUS-07)
  lateCancelRefundPercent: 50, // 하루 전이 지난 뒤 취소 환불 비율, 가게가 정하지 않았을 때(FR-RSV-05 · 06)
  couponReserveDays: 7, // 쿠폰 예약 기한 기본(FR-CPN-01)
  couponRedownloadDays: 30, // 같은 쿠폰 행사 다시 받기(FR-CPN-04)
});

const D = POLICY_DEFAULTS;

/** 확정 목업 GuestPolicy의 7개 항목 순서. 문구는 정책 · 규칙이 우선이라 기획 문서(03-requirements)를 따른다 */
const SECTIONS = Object.freeze([
  {
    title: '예약금과 취소',
    lines: [
      '예약 시각 24시간 전까지 취소하면 예약금을 100% 돌려받아요. 가게 취소 마감과 상관없이 항상 보장돼요.',
      `그 뒤부터 가게 취소 마감 전까지는 가게가 정한 비율만큼 돌려받아요(정하지 않았으면 ${D.lateCancelRefundPercent}%).`,
      '마감이 지나면 취소할 수 없어요.',
    ],
  },
  {
    title: '추천 쿠폰',
    lines: [
      "회원만 추천 카드에서 '쿠폰 다운로드'를 눌러 받을 수 있어요.",
      `받은 뒤 가게가 정한 기간(기본 ${D.couponReserveDays}일) 안에 예약을 신청하면 그 예약에 적용되고, 방문일이 기간을 넘어도 혜택은 유지돼요.`,
      `같은 가게의 같은 쿠폰 행사는 받은 때부터 ${D.couponRedownloadDays}일에 한 번만 받을 수 있어요.`,
      '예약 한 건에는 쿠폰 한 장만 직접 골라 적용해요.',
      "방문 전에 예약을 취소하면 그 쿠폰은 '적용 취소'가 되고 다시 발급되지 않아요.",
    ],
  },
  {
    title: '다음 코스 예약',
    lines: [
      "기준 가게 예약을 신청하면(승인 전이어도) 추천 가게의 '다음 코스로 예약하기'가 열려요.",
      '같은 날이면 기준 가게 예약 시각보다 늦은 시간만 고를 수 있고, 다른 날짜는 모든 시간을 고를 수 있어요.',
      '추천 가게를 따로 일반 예약하는 것은 언제든 가능해요.',
    ],
  },
  {
    title: '개인정보 보관 · 파기',
    lines: [
      `비회원 정보는 마지막 방문 후 ${D.guestRetentionMonths}개월이 지나면 이름 · 전화번호 · 사장님 메모 · 지연 입력 도착 시간을 지우고, 누구인지 알 수 없는 집계만 남겨요.`,
      `방문 기록이 없는 비회원은 마지막 예약이 취소된 뒤 ${D.noVisitRetentionDays}일이 지나면 같은 방식으로 지워요.`,
      '회원 정보는 이 파기 대상이 아니에요.',
    ],
  },
  {
    title: '가게 간 정보 공유',
    lines: [
      '예약하려면 가게 간 정보 공유에 동의해야 해요. 동의하지 않으면 예약할 수 없어요.',
      '공유 항목: 이름, 연락처, 인원, 방문 일시, 방문한 가게 목록, 성별 · 연령대. 이 중 이름 · 연락처 · 방문한 가게 목록은 꼭 필요한 항목이에요.',
      '공유한 정보는 다음 방문 추천과 집계 계산에만 써요.',
      "사장님은 자기 가게 예약과 '추천으로 왔는지'만 보고, 어느 가게에서 보냈는지는 보지 못해요. 상인회는 묶음 숫자만 봐요.",
    ],
  },
  {
    title: '지역 인기 추천',
    lines: [
      "방문 기록이 부족할 때(비회원, 서비스 초기, 새 가게)는 공공데이터로 만든 기본값으로 추천하고 '지역 인기 가게'로 표시해요.",
      '기본값은 가게 매력도 · 거리 · 업종이 함께 모인 정도 · 시간대(요일 포함)를 보고, 성별 · 연령대를 알면 손님층도 반영해요.',
      "같은 날 이어서 방문한 기록이 쌓일수록 실제 기록에 가까워지고, 그때는 '방문 데이터 기반'으로 표시해요.",
    ],
  },
  {
    title: '위치 정보',
    lines: ['현재 위치(GPS)를 쓰지 않아요.', '가게 사이 도보 정보는 경로가 확인되지 않으면 확인되지 않았다고 표시해요.'],
  },
]);

/**
 * 정책 · 개인정보(G-12). 마이(G-10)에서 들어온다. 목업처럼 항목을 하나씩 펼쳐 보고(한 번에 하나), 처음엔 첫 항목이 열려 있다.
 */
export default function PolicyPage() {
  const [openIndex, setOpenIndex] = useState(0);
  return (
    <main className={styles.page}>
      <p className={styles.lead}>로코리의 시연 정책이에요. 실제 서비스 정책과 다를 수 있어요.</p>
      {SECTIONS.map((section, index) => {
        const open = openIndex === index;
        const panelId = `policy-panel-${index}`;
        return (
          <section key={section.title} className={styles.card}>
            <h2 className={styles.heading}>
              <button
                type="button"
                className={styles.toggle}
                aria-expanded={open}
                aria-controls={panelId}
                onClick={() => setOpenIndex(open ? -1 : index)}
              >
                <span>{section.title}</span>
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                  className={open ? `${styles.chevron} ${styles.chevronOpen}` : styles.chevron}
                >
                  <path d="M6 9l6 6 6-6" />
                </svg>
              </button>
            </h2>
            <ul id={panelId} className={styles.body} hidden={!open}>
              {section.lines.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          </section>
        );
      })}
    </main>
  );
}
