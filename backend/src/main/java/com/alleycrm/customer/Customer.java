package com.alleycrm.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 손님(06-data CUSTOMER). 테이블은 Flyway가 만들고, 여기서는 지금 쓰는 칸만 매핑한다. */
@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    static final String TYPE_MEMBER = "member";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "name", length = 20)
    private String name;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "age_group", length = 20)
    private String ageGroup;

    static Customer member(long accountId, String name, String gender, String ageGroup) {
        Customer c = new Customer();
        c.type = TYPE_MEMBER;
        c.accountId = accountId;
        c.name = name;
        c.gender = gender;
        c.ageGroup = ageGroup;
        return c;
    }
}
