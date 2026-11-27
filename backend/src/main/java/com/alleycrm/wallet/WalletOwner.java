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

    /**
     * 가게만 마이너스가 될 수 있다(수수료, 기획 11-25 (4)). 회원은 예약금보다 적으면 거절(FR-RSV-08),
     * 상인회 기금은 충전할 수 없어 마이너스가 될 수 없다(기획 11-25 (5)).
     */
    public boolean mayGoNegative() {
        return STORE.equals(type);
    }
}
