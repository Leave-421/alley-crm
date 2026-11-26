package com.alleycrm.store;

/** 가게 가입 상태 값. CONTRACTS.md 「3」 `store.joinStatus`와 같아야 한다. */
public final class StoreJoinStatus {

    public static final String REQUESTED = "requested";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String WITHDRAW_REQUESTED = "withdraw_requested";
    public static final String WITHDRAWN = "withdrawn";

    static final String VALUES = "requested|approved|rejected|withdraw_requested|withdrawn";

    private StoreJoinStatus() {
    }
}
