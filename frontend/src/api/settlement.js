import { api } from '@/api/client.js';

// 사장님 소개 · 정산 화면(O-08)이 쓰는 API — CONTRACTS_API.md 「사장님 소개 수수료 내역 · 소개 실적 (D16)」 ·
// 「가상 잔액 · 거래 내역 (D9)」 · 「가상 충전 (D10)」. 가게 · 지갑은 서버가 로그인 계정으로 정한다.
// 실제 결제 · 송금은 없다(NFR-02, 가상 잔액).

/** 월 정산 상태(CONTRACTS.md 「3」 settlement.status). 없으면(null) '잠정' */
export const SETTLEMENT_STATUS_LABELS = Object.freeze({ aggregated: '집계됨', confirmed: '확정' });

/** 거래 종류(CONTRACTS.md 「3」 walletTx.type)와 화면 글자 */
export const WALLET_TX_LABELS = Object.freeze({
  charge: '가상 충전',
  deposit_paid: '예약금 납부',
  deposit_refunded: '예약금 환불',
  referral_fee_charged: '소개 수수료',
  fund_deposited: '공동 기금 입금',
  fund_budget_reserved: '기금 예산 잡기',
  fund_budget_released: '기금 예산 해제',
  fee_difference_charged: '수수료 차액',
  fee_difference_deposited: '수수료 차액 기금 입금',
});

/** 가상 충전 한 번 금액(D10 「정책에 따라 정한 것」) */
export const CHARGE_LIMITS = Object.freeze({ min: 1_000, max: 1_000_000 });

/** 월별 소개 수수료 내역. month = 'YYYY-MM'(정산 달 = 방문 처리된 달) */
export function getReferralFees(month) {
  return api.get('/api/v1/owner/referral-fees', { query: { month } });
}

/** 소개 실적 { month, sentCount, receivedCount }. month 없으면 전체 기간 */
export function getReferralStats(month) {
  return api.get('/api/v1/owner/referral-stats', { query: month ? { month } : undefined });
}

/** 내 가상 잔액 { ownerType, balance, needsCharge } */
export function getMyWallet() {
  return api.get('/api/v1/wallets/me');
}

/** 내 거래 내역(최근 것부터, 공통 페이지) */
export function getMyWalletTransactions({ page = 0, size = 20 } = {}) {
  return api.get('/api/v1/wallets/me/transactions', { query: { page, size } });
}

/** 가상 충전. 응답은 충전 뒤 잔액(getMyWallet과 같은 모양) */
export function chargeMyWallet(amount) {
  return api.post('/api/v1/wallets/me/charge', { amount });
}
