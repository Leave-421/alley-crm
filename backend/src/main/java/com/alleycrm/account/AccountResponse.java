package com.alleycrm.account;

/** 계약 `Account` 응답. ID는 문자열. */
record AccountResponse(String id, String loginId, String role, String storeId, String regionId) {

    static AccountResponse from(AccountPrincipal p) {
        return new AccountResponse(String.valueOf(p.id()), p.loginId(), p.role(),
                p.storeId() == null ? null : String.valueOf(p.storeId()),
                p.regionId() == null ? null : String.valueOf(p.regionId()));
    }
}
