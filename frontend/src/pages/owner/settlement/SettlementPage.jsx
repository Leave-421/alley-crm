import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { getClock } from '@/api/demoClock.js';
import { getMyWallet, getMyWalletTransactions, getReferralFees, getReferralStats, SETTLEMENT_STATUS_LABELS, WALLET_TX_LABELS } from '@/api/settlement.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import WalletCard from './WalletCard.jsx';
import { addMonths, formatDateTime, formatMonth, formatRate, formatSignedWon, formatWon, toMonthKey } from './settlementFormat.js';
import styles from './Settlement.module.css';

const PAGE_SIZE = 20;

/** 서버 기준 이번 달. 시연 도구로 시각을 앞당길 수 있어서(D18) 서버 시각을 먼저 묻고, 꺼져 있으면 기기 시각. */
function useThisMonth() {
  const [month, setMonth] = useState(null);
  useEffect(() => {
    let active = true;
    getClock()
      .then((clock) => Date.parse(clock.now))
      .catch(() => Date.now())
      .then((ms) => {
        if (active) setMonth(toMonthKey(Number.isNaN(ms) ? Date.now() : ms));
      });
    return () => {
      active = false;
    };
  }, []);
  return month;
}

/**
 * O-08 소개 · 정산(B14). 기준: 07-screens O-08, FR-STL-02 · 03 · 04 · 05 · 06, NFR-04,
 * CONTRACTS_API.md D9 · D10 · D16. 보낸 가게 이름은 어디에도 없다(FR-CUS-05).
 * 건별 수수료 내역(O-15)은 A29가 만든다.
 */
export default function SettlementPage() {
  const thisMonth = useThisMonth();
  const [selected, setSelected] = useState(null);
  const month = selected ?? thisMonth;
  const [notice, setNotice] = useState(null);

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>소개 · 정산</h1>
        <p className={styles.lead}>추천으로 온 손님의 소개 수수료와 가게 가상 잔액을 봐요. 실제 결제 · 송금은 없어요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      <WalletSection onNotice={setNotice} />

      {month && (
        <>
          <MonthlyFees
            month={month}
            isThisMonth={month === thisMonth}
            // 빠르게 여러 번 눌러도 누른 만큼 움직이게 앞 값 기준으로 계산한다(#241)
            onMove={(delta) => setSelected((previous) => addMonths(previous ?? thisMonth, delta))}
            onThisMonth={() => setSelected(null)}
          />
          <ReferralStats month={month} />
        </>
      )}

      <nav className={styles.links} aria-label="소개 · 정산 바로가기">
        <Link to="/owner">오늘 예약 보기 ›</Link>
        <Link to="/owner/settlement/fees">건별 수수료 내역 ›</Link>
      </nav>
    </main>
  );
}

/** 잔액 · 충전 · 거래 내역. 충전하면 잔액과 내역을 다시 읽는다. */
function WalletSection({ onNotice }) {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', wallet: null, transactions: [], total: 0, error: null });
  const [extra, setExtra] = useState([]); // '더 보기'로 붙인 다음 페이지들
  const state = result.key === reloadCount ? result : { status: 'loading', wallet: null, transactions: [], total: 0, error: null };

  useEffect(() => {
    let active = true;
    Promise.all([getMyWallet(), getMyWalletTransactions({ page: 0, size: PAGE_SIZE })])
      .then(([wallet, page]) => {
        if (active) setResult({ key: reloadCount, status: 'ready', wallet, transactions: page.items, total: page.totalElements, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', wallet: null, transactions: [], total: 0, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  function reload() {
    setExtra([]);
    setReloadCount((count) => count + 1);
  }

  async function loadMore() {
    const shown = state.transactions.length + extra.length;
    const page = await getMyWalletTransactions({ page: Math.floor(shown / PAGE_SIZE), size: PAGE_SIZE });
    setExtra((previous) => [...previous, ...page.items]);
  }

  if (state.status === 'loading') return <p role="status">가상 잔액을 불러오고 있어요.</p>;
  if (state.status === 'error') {
    return (
      <section className={styles.card} role="alert">
        <p>가상 잔액을 불러오지 못했어요. {state.error?.message}</p>
        <Button variant="secondary" onClick={reload}>
          다시 불러오기
        </Button>
      </section>
    );
  }

  const transactions = [...state.transactions, ...extra];
  return (
    <>
      <WalletCard
        wallet={state.wallet}
        onCharged={(wallet, amount) => {
          onNotice(`${formatWon(amount)}을 가상 충전했어요. 잔액 ${formatWon(wallet.balance)}.`);
          reload();
        }}
      />
      <section className={styles.card} aria-label="거래 내역">
        <h2 className={styles.sectionTitle}>거래 내역</h2>
        {transactions.length === 0 ? (
          <p className={styles.hint}>아직 거래가 없어요.</p>
        ) : (
          <ul className={styles.txList}>
            {transactions.map((tx) => (
              <li key={tx.id} className={styles.tx}>
                <span>
                  <strong>{WALLET_TX_LABELS[tx.type] ?? tx.type}</strong>
                  <time dateTime={tx.createdAt} className={styles.muted}>
                    {formatDateTime(tx.createdAt)}
                  </time>
                </span>
                <span className={tx.amount < 0 ? styles.out : styles.in}>{formatSignedWon(tx.amount)}</span>
              </li>
            ))}
          </ul>
        )}
        {transactions.length < state.total && (
          <Button variant="secondary" onClick={loadMore}>
            더 보기
          </Button>
        )}
      </section>
    </>
  );
}

/** 그 달 소개 수수료 합계 · 쿠폰 비용 · 공동 기금 적립(FR-STL-02 · 03 · 05). 수수료율은 설정값(NFR-04). */
function MonthlyFees({ month, isThisMonth, onMove, onThisMonth }) {
  const [result, setResult] = useState({ key: null, status: 'ready', data: null, error: null });
  const state = result.key === month ? result : { status: 'loading', data: null, error: null };

  useEffect(() => {
    let active = true;
    getReferralFees(month)
      .then((data) => {
        if (active) setResult({ key: month, status: 'ready', data, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: month, status: 'error', data: null, error });
      });
    return () => {
      active = false;
    };
  }, [month]);

  const data = state.data;
  const status = data?.settlementStatus;
  return (
    <section className={styles.card} aria-label={`${formatMonth(month)} 소개 수수료`}>
      <div className={styles.monthNav}>
        <button type="button" className={styles.navButton} onClick={() => onMove(-1)} aria-label="이전 달">
          ‹
        </button>
        <h2 className={styles.sectionTitle}>{isThisMonth ? `이번 달(${formatMonth(month)})` : formatMonth(month)} 소개 수수료</h2>
        <button type="button" className={styles.navButton} onClick={() => onMove(1)} aria-label="다음 달">
          ›
        </button>
        {!isThisMonth && (
          <Button variant="secondary" onClick={onThisMonth}>
            이번 달
          </Button>
        )}
      </div>

      {state.status === 'loading' && <p role="status">수수료 내역을 불러오고 있어요.</p>}
      {state.status === 'error' && <p role="alert">수수료 내역을 불러오지 못했어요. {state.error?.message}</p>}
      {data && (
        <>
          <div className={styles.statusRow}>
            <Badge tone={status === 'confirmed' ? 'confirmed' : 'requested'}>{status ? SETTLEMENT_STATUS_LABELS[status] : '잠정'}</Badge>
            <span className={styles.muted}>수수료율 {formatRate(data.referralFeeRate)}(설정값)</span>
          </div>
          <dl className={styles.kpis}>
            <div className={styles.kpi}>
              <dt>낸 소개 수수료</dt>
              <dd>{formatWon(data.totalFee)}</dd>
            </div>
            <div className={styles.kpi}>
              <dt>소개 건수</dt>
              <dd>{data.count}건</dd>
            </div>
            <div className={styles.kpi}>
              <dt>공동 기금 적립</dt>
              <dd>{formatWon(data.totalFee)}</dd>
            </div>
            <div className={styles.kpi}>
              <dt>쿠폰 비용(별도)</dt>
              <dd>{formatWon(data.totalCouponCost)}</dd>
            </div>
          </dl>
          <p className={styles.hint}>
            수수료는 쿠폰 할인 뒤 방문 금액 × {formatRate(data.referralFeeRate)}이고, 방문 처리 때 이미 가상 잔액에서 공동 기금으로 빠져나갔어요. 월 정산
            확정은 확인 기록이라 잔액이 다시 바뀌지 않아요. 쿠폰 비용은 수수료와 따로 내 가게가 부담해요.
          </p>
          {data.items.length > 0 && (
            <ul className={styles.feeList} aria-label="소개 수수료 내역">
              {data.items.map((fee) => (
                <li key={fee.reservationId} className={styles.tx}>
                  <span>
                    <strong>{fee.maskedName ?? '이름 없음'}</strong>
                    <span className={styles.muted}>
                      {formatDateTime(fee.visitedAt)} · 추천 경유 · 계산 금액 {formatWon(fee.feeBase)}
                    </span>
                  </span>
                  <span className={styles.out}>{formatWon(fee.feeAmount)}</span>
                </li>
              ))}
            </ul>
          )}
          {data.items.length === 0 && <p className={styles.hint}>이 달에는 소개 수수료가 없어요.</p>}
        </>
      )}
    </section>
  );
}

/** 소개 실적: 보낸 손님 수 · 받은 손님 수(FR-STL-04). 보낸 손님에게는 돈을 받지 않는다. */
function ReferralStats({ month }) {
  const [result, setResult] = useState({ key: null, monthly: null, total: null, error: null });
  const ready = result.key === month;

  useEffect(() => {
    let active = true;
    Promise.all([getReferralStats(month), getReferralStats()])
      .then(([monthly, total]) => {
        if (active) setResult({ key: month, monthly, total, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: month, monthly: null, total: null, error });
      });
    return () => {
      active = false;
    };
  }, [month]);

  return (
    <section className={styles.card} aria-label="소개 실적">
      <h2 className={styles.sectionTitle}>소개 실적</h2>
      {!ready && <p role="status">소개 실적을 불러오고 있어요.</p>}
      {ready && result.error && <p role="alert">소개 실적을 불러오지 못했어요. {result.error.message}</p>}
      {ready && result.monthly && (
        <>
          <dl className={styles.kpis}>
            <div className={styles.kpi}>
              <dt>보낸 손님</dt>
              <dd>
                {result.monthly.sentCount}명 <span className={styles.muted}>(전체 {result.total.sentCount}명)</span>
              </dd>
            </div>
            <div className={styles.kpi}>
              <dt>받은 손님</dt>
              <dd>
                {result.monthly.receivedCount}명 <span className={styles.muted}>(전체 {result.total.receivedCount}명)</span>
              </dd>
            </div>
          </dl>
          <p className={styles.hint}>{formatMonth(month)} 기준이에요. 우리 가게가 보낸 손님에 대해서는 수수료를 받지 않아요(돈이 오가지 않아요).</p>
        </>
      )}
    </section>
  );
}
