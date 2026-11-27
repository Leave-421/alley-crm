import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { WALLET_TX_LABELS, chargeMyWallet, getMyWallet, getMyWalletTransactions } from '@/api/settlement.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import styles from './MyPage.module.css';

const CHARGE_AMOUNTS = Object.freeze([10_000, 30_000, 50_000]); // 목업의 충전 버튼(+10,000 · +30,000 · +50,000)
const TX_PAGE_SIZE = 10;

const won = (amount) => `${amount.toLocaleString('ko-KR')}원`;
const txTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

/**
 * 마이(G-10). 회원: 계정 · 가상 잔액(예약금 안내) · 가상 충전 · 거래 내역. 비회원: 로그인 안내.
 * 공통: 내 예약 · 알림함 · 정책 · 개인정보(G-12) · 시연 역할 전환.
 * 회원정보(이름 · 성별 · 연령대) 확인 · 수정은 조회 · 수정 API가 계약에 아직 없어 아이디만 보여준다.
 */
export default function MyPage() {
  const { status, role, account } = useSession();
  const loggedIn = status === SESSION_STATUS.AUTHENTICATED;
  const isMember = loggedIn && role === 'customer';

  let who = '비회원';
  let whoSub = '로그인하면 예약 · 쿠폰을 모아볼 수 있어요';
  if (isMember) {
    who = account?.loginId ?? '회원';
    whoSub = '시연 계정 · 이름 · 성별 · 연령대 수정은 준비 중이에요';
  } else if (loggedIn) {
    who = account?.loginId ?? '';
    whoSub = '손님 계정이 아니에요. 시연 역할 전환에서 손님으로 바꿔 주세요';
  }

  return (
    <main className={styles.page}>
      <section className={`${styles.card} ${styles.profile}`} aria-label="내 계정">
        <span className={styles.avatar} aria-hidden="true">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="8" r="4" />
            <path d="M4 21a8 8 0 0116 0" />
          </svg>
        </span>
        <div className={styles.profileText}>
          <div className={styles.who}>{who}</div>
          <div className={styles.cap}>{whoSub}</div>
        </div>
      </section>

      {isMember ? (
        <WalletCard />
      ) : (
        !loggedIn && (
          <Link to="/login" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.loginButton}`}>
            로그인하고 쿠폰 받기
          </Link>
        )
      )}

      <nav className={`${styles.card} ${styles.menu}`} aria-label="마이 메뉴">
        <MenuLink to="/reservations" label="내 예약" />
        <MenuLink to="/notifications" label="알림함" />
      </nav>
      <nav className={`${styles.card} ${styles.menu}`} aria-label="안내 · 시연">
        <MenuLink to="/policy" label="정책 · 개인정보 안내" />
        <MenuLink to="/demo" label="시연 역할 전환" />
      </nav>
    </main>
  );
}

function MenuLink({ to, label }) {
  return (
    <Link to={to} className={styles.menuRow}>
      <b className={styles.menuLabel}>{label}</b>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={styles.chevron}>
        <path d="M9 6l6 6-6 6" />
      </svg>
    </Link>
  );
}

/** 회원의 가상 잔액 · 충전 · 거래 내역(D9 · D10). 실제 결제는 없다 */
function WalletCard() {
  const [wallet, setWallet] = useState(null); // { data } | { error }
  const [txPages, setTxPages] = useState({ items: [], page: -1, totalPages: 0, error: null });
  const [reload, setReload] = useState(0);
  const [charging, setCharging] = useState(false);
  const [chargeError, setChargeError] = useState(null);

  useEffect(() => {
    let active = true;
    getMyWallet()
      .then((data) => active && setWallet({ data }))
      .catch((error) => active && setWallet({ error }));
    getMyWalletTransactions({ page: 0, size: TX_PAGE_SIZE })
      .then((data) => active && setTxPages({ items: data.items, page: data.page, totalPages: data.totalPages, error: null }))
      .catch((error) => active && setTxPages((prev) => ({ ...prev, error })));
    return () => {
      active = false;
    };
  }, [reload]);

  async function charge(amount) {
    setCharging(true);
    setChargeError(null);
    try {
      const data = await chargeMyWallet(amount);
      setWallet({ data });
      setReload((n) => n + 1);
    } catch (error) {
      setChargeError(error);
    } finally {
      setCharging(false);
    }
  }

  async function loadMore() {
    try {
      const data = await getMyWalletTransactions({ page: txPages.page + 1, size: TX_PAGE_SIZE });
      setTxPages((prev) => ({ items: [...prev.items, ...data.items], page: data.page, totalPages: data.totalPages, error: null }));
    } catch (error) {
      setTxPages((prev) => ({ ...prev, error }));
    }
  }

  return (
    <>
      <section className={`${styles.card} ${styles.wallet}`} aria-labelledby="my-wallet">
        <div className={styles.walletHead}>
          <h2 id="my-wallet" className={styles.label}>
            가상 잔액
          </h2>
          <b className={`${styles.balance} ${styles.num}`} aria-live="polite">
            {wallet?.data ? won(wallet.data.balance) : wallet?.error ? '불러오지 못했어요' : '…'}
          </b>
        </div>
        <p className={styles.cap}>예약금은 가상 잔액에서 빠지고, 취소하면 돌아와요. 실제 돈은 움직이지 않아요.</p>
        <div className={styles.charges}>
          {CHARGE_AMOUNTS.map((amount) => (
            <button key={amount} type="button" className={styles.charge} disabled={charging} onClick={() => charge(amount)} aria-label={`${won(amount)} 가상 충전`}>
              +{amount.toLocaleString('ko-KR')}
            </button>
          ))}
        </div>
        {chargeError && (
          <p className={styles.error} role="alert">
            {chargeError.message || '충전하지 못했어요. 잠시 뒤 다시 시도해 주세요.'}
          </p>
        )}
      </section>

      <section className={`${styles.card} ${styles.history}`} aria-labelledby="my-transactions">
        <h2 id="my-transactions" className={styles.label}>
          거래 내역
        </h2>
        {txPages.error && txPages.items.length === 0 && <p className={styles.cap}>거래 내역을 불러오지 못했어요.</p>}
        {!txPages.error && txPages.page >= 0 && txPages.items.length === 0 && <p className={styles.cap}>아직 거래가 없어요. 충전하거나 예약하면 여기에 남아요.</p>}
        {txPages.items.length > 0 && (
          <ul className={styles.txList}>
            {txPages.items.map((tx) => (
              <li key={tx.id} className={styles.tx}>
                <div>
                  <div className={styles.txType}>{WALLET_TX_LABELS[tx.type] ?? tx.type}</div>
                  <div className={styles.cap}>{txTime.format(new Date(tx.createdAt))}</div>
                </div>
                <b className={`${styles.num} ${tx.amount >= 0 ? styles.plus : styles.minus}`}>
                  {tx.amount >= 0 ? '+' : '−'}
                  {won(Math.abs(tx.amount))}
                </b>
              </li>
            ))}
          </ul>
        )}
        {txPages.page + 1 < txPages.totalPages && (
          <button type="button" className={styles.more} onClick={loadMore}>
            더 보기
          </button>
        )}
      </section>
    </>
  );
}
