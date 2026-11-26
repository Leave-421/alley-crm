import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import { getClock } from '@/api/demoClock.js';
import {
  confirmAssociationSettlement,
  getAssociationSettlement,
  getMyWallet,
  getMyWalletTransactions,
  WALLET_TX_LABELS,
} from '@/api/settlement.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { addMonths, formatDateTime, formatMonth, formatSignedWon, formatWon, toMonthKey } from '@/pages/owner/settlement/settlementFormat.js';
import styles from '@/pages/owner/settlement/Settlement.module.css';

const PAGE_SIZE = 20;

/** 서버 기준 이번 달(D18 시연 시계를 따른다, 꺼져 있으면 기기 시각) */
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
 * A-04 공동 기금 · 정산(B18). 기준: 07-screens A-04, FR-STL-05 · 06 · FR-ANL-05, CONTRACTS_API.md D17 · D9.
 * 가게 단위 묶음 숫자만 보여 주고 손님 정보는 없다. 평균 방문 금액이 낮은지 판단하는 표시는 하지 않는다(11-24 (5)).
 * 건별 적립 내역(A-07)은 A28이 만든다. 설정값(수수료율)을 바꾸는 기능은 결정 기록에 없어 두지 않는다.
 */
export default function FundSettlementPage() {
  const thisMonth = useThisMonth();
  const [selected, setSelected] = useState(null);
  const defaultMonth = thisMonth ? addMonths(thisMonth, -1) : null; // 확정할 수 있는 지난달부터 연다
  const month = selected ?? defaultMonth;
  const [notice, setNotice] = useState(null);
  const [fundReload, setFundReload] = useState(0);

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>공동 기금 · 정산</h1>
        <p className={styles.lead}>소개 수수료가 모인 공동 기금과 달마다의 정산을 확인하고 확정해요. 실제 돈은 오가지 않아요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {month && (
        <MonthlySettlement
          month={month}
          isThisMonth={month === thisMonth}
          onMove={(delta) => {
            setNotice(null);
            // 빠르게 여러 번 눌러도 누른 만큼 움직이게 앞 값 기준으로 계산한다
            setSelected((previous) => addMonths(previous ?? defaultMonth, delta));
          }}
          onConfirmed={(settlement) => {
            setNotice(`${formatMonth(settlement.month)} 정산을 확정했어요. 확인 기록이라 잔액 · 수수료는 바뀌지 않아요.`);
            setFundReload((count) => count + 1);
          }}
        />
      )}

      <FundSection reloadKey={fundReload} />

      <nav className={styles.links} aria-label="정산 바로가기">
        <Link to="/association/settlement/fund">공동 기금 적립 내역 ›</Link>
      </nav>
    </main>
  );
}

/** 월 정산: 확인 · 확정, 가게별 소개 건 수 · 수수료 · 평균 방문 금액(쿠폰 할인 후) · 쿠폰 비용 */
function MonthlySettlement({ month, isThisMonth, onMove, onConfirmed }) {
  const [reloadCount, setReloadCount] = useState(0);
  const key = `${month}#${reloadCount}`;
  const [result, setResult] = useState({ key: null, status: 'ready', data: null, error: null });
  const state = result.key === key ? result : { status: 'loading', data: null, error: null };
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let active = true;
    getAssociationSettlement(key.split('#')[0])
      .then((data) => {
        if (active) setResult({ key, status: 'ready', data, error: null });
      })
      .catch((caught) => {
        if (active) setResult({ key, status: 'error', data: null, error: caught });
      });
    return () => {
      active = false;
    };
  }, [key]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const data = await confirmAssociationSettlement(month);
      setResult({ key, status: 'ready', data, error: null });
      setConfirming(false);
      onConfirmed(data);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '확정하지 못했어요.');
      if (caught instanceof ApiError && caught.code === 'conflict') setReloadCount((count) => count + 1);
    } finally {
      setBusy(false);
    }
  }

  const data = state.data;
  return (
    <section className={styles.card} aria-label={`${formatMonth(month)} 월 정산`}>
      <div className={styles.monthNav}>
        <button type="button" className={styles.navButton} onClick={() => onMove(-1)} aria-label="이전 달">
          ‹
        </button>
        <h2 className={styles.sectionTitle}>
          {formatMonth(month)} 정산{isThisMonth ? '(이번 달)' : ''}
        </h2>
        <button type="button" className={styles.navButton} onClick={() => onMove(1)} aria-label="다음 달">
          ›
        </button>
      </div>

      {state.status === 'loading' && <p role="status">정산을 불러오고 있어요.</p>}
      {state.status === 'error' && <p role="alert">정산을 불러오지 못했어요. {state.error?.message}</p>}
      {data && (
        <>
          <div className={styles.statusRow}>
            <Badge tone={data.status === 'confirmed' ? 'confirmed' : 'requested'}>{data.status === 'confirmed' ? '확정' : '집계됨(확정 전)'}</Badge>
            {data.confirmedAt && <span className={styles.muted}>{formatDateTime(data.confirmedAt)} 확정</span>}
          </div>
          <dl className={styles.kpis}>
            <div className={styles.kpi}>
              <dt>소개 수수료 합계</dt>
              <dd>{formatWon(data.totalFee)}</dd>
            </div>
            <div className={styles.kpi}>
              <dt>소개 건수</dt>
              <dd>{data.count}건</dd>
            </div>
          </dl>

          {data.stores.length === 0 ? (
            <p className={styles.hint}>이 달에는 소개 수수료가 없어요.</p>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className={styles.muted}>가게별 소개 건(방문 처리된 달 기준). 평균 방문 금액은 쿠폰 할인 후 금액이에요.</caption>
                <thead>
                  <tr>
                    <th scope="col">가게</th>
                    <th scope="col">소개 건수</th>
                    <th scope="col">수수료</th>
                    <th scope="col">평균 방문 금액</th>
                    <th scope="col">쿠폰 비용</th>
                  </tr>
                </thead>
                <tbody>
                  {data.stores.map((store) => (
                    <tr key={store.storeId}>
                      <th scope="row">{store.storeName}</th>
                      <td>{store.count}건</td>
                      <td>{formatWon(store.totalFee)}</td>
                      <td>{formatWon(store.avgVisitAmount)}</td>
                      <td>{formatWon(store.couponCost)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <p className={styles.hint}>
            수수료는 방문 처리 때 받은 가게의 가상 잔액에서 공동 기금으로 이미 옮겨졌어요. 확정은 이 달 정산을 확인했다는 기록이라 잔액 · 수수료가 바뀌지 않아요.
          </p>

          {data.status !== 'confirmed' && <ConfirmBlocker data={data} isThisMonth={isThisMonth} />}

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          {data.canConfirm &&
            (confirming ? (
              <div className={styles.form}>
                <p className={styles.warning}>{formatMonth(month)} 정산을 확정할까요? 확정하면 되돌릴 수 없어요.</p>
                <div className={styles.actions}>
                  <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy}>
                    돌아가기
                  </Button>
                  <Button onClick={confirm} disabled={busy}>
                    {busy ? '확정 중' : '확정하기'}
                  </Button>
                </div>
              </div>
            ) : (
              <Button onClick={() => setConfirming(true)}>정산 확정</Button>
            ))}
        </>
      )}
    </section>
  );
}

/** 확정할 수 없는 이유(D17 canConfirm: 그 달이 끝남 · 처리 안 된 신고 0) */
function ConfirmBlocker({ data, isThisMonth }) {
  if (data.canConfirm) return null;
  if (data.unprocessedReportCount > 0) {
    return (
      <p className={styles.warning}>
        처리되지 않은 신고가 {data.unprocessedReportCount}건 있어 확정할 수 없어요. 신고를 먼저 처리해 주세요(FR-STL-05).
      </p>
    );
  }
  return <p className={styles.hint}>{isThisMonth ? '이번 달이 끝난 뒤에 확정할 수 있어요.' : '이 달이 끝난 뒤에 확정할 수 있어요.'}</p>;
}

/** 공동 기금 잔액과 거래 내역(FR-STL-05 · 06). 상인회의 지갑 = 권역 공동 기금(D9). */
function FundSection({ reloadKey }) {
  const [result, setResult] = useState({ key: null, status: 'ready', wallet: null, transactions: [], total: 0, error: null });
  const [extra, setExtra] = useState([]);
  const state = result.key === reloadKey ? result : { status: 'loading', wallet: null, transactions: [], total: 0, error: null };

  useEffect(() => {
    let active = true;
    Promise.all([getMyWallet(), getMyWalletTransactions({ page: 0, size: PAGE_SIZE })])
      .then(([wallet, page]) => {
        if (active) {
          setExtra([]);
          setResult({ key: reloadKey, status: 'ready', wallet, transactions: page.items, total: page.totalElements, error: null });
        }
      })
      .catch((error) => {
        if (active) setResult({ key: reloadKey, status: 'error', wallet: null, transactions: [], total: 0, error });
      });
    return () => {
      active = false;
    };
  }, [reloadKey]);

  async function loadMore() {
    const shown = state.transactions.length + extra.length;
    const page = await getMyWalletTransactions({ page: Math.floor(shown / PAGE_SIZE), size: PAGE_SIZE });
    setExtra((previous) => [...previous, ...page.items]);
  }

  if (state.status === 'loading') return <p role="status">공동 기금을 불러오고 있어요.</p>;
  if (state.status === 'error') return <p role="alert">공동 기금을 불러오지 못했어요. {state.error?.message}</p>;

  const transactions = [...state.transactions, ...extra];
  return (
    <section className={styles.card} aria-label="공동 기금">
      <h2 className={styles.sectionTitle}>공동 기금 잔액</h2>
      <p className={styles.balance}>{formatWon(state.wallet.balance)}</p>
      <p className={styles.hint}>소개 수수료가 모여요. 상인회 쿠폰 행사 예산도 여기서 잡아요. 공동 기금은 0원보다 적어지지 않아요.</p>
      <h3 className={styles.label}>거래 내역</h3>
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
  );
}
