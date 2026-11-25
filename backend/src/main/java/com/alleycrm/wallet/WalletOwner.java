package com.alleycrm.wallet;

/**
 * 지갑 주인. type은 CONTRACTS.md 「3」 `wallet.ownerType`, id는 customer.id · store.id · region.id(fund).
 */
public record WalletOwner(String type, long id) {

    public static final String CUSTOMER = "customer";
    public static final String STORE = "store";
    public static final String FUND = "fund";

    public static WalletOwner customer(long customerId) {
        return new WalletOwner(CUSTOMER, customerId);
    }

    public static WalletOwner store(long storeId) {
        return new WalletOwner(STORE, storeId);
    }

    public static WalletOwner fund(long regionId) {
        return new WalletOwner(FUND, regionId);
    }

    /** 회원 잔액만 마이너스가 될 수 없다. 가게는 수수료로 마이너스가 될 수 있다(기획 11-25 (3)) */
    public boolean mayGoNegative() {
        return !CUSTOMER.equals(type);
    }
}
