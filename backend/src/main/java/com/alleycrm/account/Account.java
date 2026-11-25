package com.alleycrm.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계정(06-data ACCOUNT). 테이블은 Flyway가 만들고, 여기서는 지금 쓰는 칸만 매핑한다. */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 30)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "region_id")
    private Long regionId;

    private Account(String loginId, String passwordHash, String role, Long storeId, Long regionId) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
        this.storeId = storeId;
        this.regionId = regionId;
    }

    static Account customer(String loginId, String passwordHash) {
        return new Account(loginId, passwordHash, AccountRole.CUSTOMER, null, null);
    }

    static Account owner(String loginId, String passwordHash, long storeId) {
        return new Account(loginId, passwordHash, AccountRole.OWNER, storeId, null);
    }

    static Account association(String loginId, String passwordHash, long regionId) {
        return new Account(loginId, passwordHash, AccountRole.ASSOCIATION, null, regionId);
    }

    AccountPrincipal toPrincipal() {
        return new AccountPrincipal(id, loginId, role, storeId, regionId);
    }
}
