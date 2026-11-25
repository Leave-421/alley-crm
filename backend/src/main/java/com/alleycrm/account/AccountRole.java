package com.alleycrm.account;

/** 계정 역할 값. CONTRACTS.md 「2. 역할」 · 「3」 `account.role`과 같아야 한다. */
public final class AccountRole {

    public static final String CUSTOMER = "customer";
    public static final String OWNER = "owner";
    public static final String ASSOCIATION = "association";

    private AccountRole() {
    }
}
