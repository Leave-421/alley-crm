package com.alleycrm.wallet;

/** 거래 종류. CONTRACTS.md 「3」 `walletTx.type`과 같아야 한다. */
public final class WalletTxType {

    public static final String CHARGE = "charge";
    public static final String DEPOSIT_PAID = "deposit_paid";
    public static final String DEPOSIT_REFUNDED = "deposit_refunded";
    public static final String REFERRAL_FEE_CHARGED = "referral_fee_charged";
    public static final String FUND_DEPOSITED = "fund_deposited";
    /** 상인회 쿠폰 행사 예산 잡기 · 해제(D20, 11-25 (5)) */
    public static final String FUND_BUDGET_RESERVED = "fund_budget_reserved";
    public static final String FUND_BUDGET_RELEASED = "fund_budget_released";
    /** 방문 금액 정정의 수수료 차액(D22, 11-25 (5)) */
    public static final String FEE_DIFFERENCE_CHARGED = "fee_difference_charged";
    public static final String FEE_DIFFERENCE_DEPOSITED = "fee_difference_deposited";

    private WalletTxType() {
    }
}
