package com.alleycrm.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 가게(06-data STORE). 테이블은 Flyway가 만들고, 여기서는 가게 가입(C5)이 쓰는 칸만 매핑한다. */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "alley", nullable = false, length = 20)
    private String alley;

    @Column(name = "join_status", nullable = false, length = 20)
    private String joinStatus;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    /** 휴무 요일 mon,tue,... 빈 값이면 연중무휴 */
    @Column(name = "closed_days", nullable = false, length = 40)
    private String closedDays;

    static Store requested(long regionId, StoreJoinInfo info) {
        Store s = new Store();
        s.regionId = regionId;
        s.joinStatus = StoreJoinStatus.REQUESTED;
        s.applyInfo(info);
        return s;
    }

    /** 거절된 가입을 고친 정보로 다시 신청 */
    void reapply(StoreJoinInfo info) {
        applyInfo(info);
        joinStatus = StoreJoinStatus.REQUESTED;
    }

    void decide(String status) {
        joinStatus = status;
    }

    private void applyInfo(StoreJoinInfo info) {
        name = info.name().strip();
        category = info.category();
        alley = info.alley();
        openTime = info.openTime();
        closeTime = info.closeTime();
        closedDays = info.closedDaysValue();
    }
}
