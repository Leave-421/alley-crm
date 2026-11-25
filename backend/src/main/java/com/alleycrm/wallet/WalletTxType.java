package com.alleycrm.wallet;

/** 거래 종류. CONTRACTS.md 「3」 `walletTx.type`과 같아야 한다. */
public final class WalletTxType {

    public static final String CHARGE = "charge";
    public static final String DEPOSIT_PAID = "deposit_paid";
    public static final String DEPOSIT_REFUNDED = "deposit_refunded";
    public static final String REFERRAL_FEE_CHARGED = "referral_fee_charged";
    public static final String FUND_DEPOSITED = "fund_deposited";

    private WalletTxType() {
    }
}
