package com.alleycrm.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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
    static final String TYPE_GUEST = "guest";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "name", length = 20)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "age_group", length = 20)
    private String ageGroup;

    /** 비회원 파기 시각. 파기되면 이름 · 전화번호가 지워져 다시 식별되지 않는다(C12). */
    @Column(name = "purged_at")
    private Instant purgedAt;

    static Customer member(long accountId, String name, String gender, String ageGroup) {
        Customer c = new Customer();
        c.type = TYPE_MEMBER;
        c.accountId = accountId;
        c.name = name;
        c.gender = gender;
        c.ageGroup = ageGroup;
        return c;
    }

    static Customer guest(String name, String phone, String gender, String ageGroup) {
        Customer c = new Customer();
        c.type = TYPE_GUEST;
        c.phone = phone;
        c.updateGuestInfo(name, gender, ageGroup);
        return c;
    }

    /** 같은 전화번호로 다시 예약하면 이름은 최신 값으로, 성별 · 연령대는 새로 보낸 값이 있을 때만 바꾼다. */
    void updateGuestInfo(String name, String gender, String ageGroup) {
        this.name = name;
        if (gender != null) {
            this.gender = gender;
        }
        if (ageGroup != null) {
            this.ageGroup = ageGroup;
        }
    }
}
