package com.alleycrm.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 가게(06-data STORE). 테이블은 Flyway가 만들고, 여기서는 가게 가입(C5) · 목록(C6) · 사장님 변경(C22)이 쓰는 칸만 매핑한다. */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    /** 영업 상태 `store.businessStatus` 값 중 영업(CONTRACTS.md 「3」) */
    static final String BUSINESS_OPEN = "open";

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

    // 영업시간은 시간대 없는 벽시계(Asia/Seoul). jdbc.time_zone=UTC 변환을 타면 JVM 시간대만큼 밀려서 드라이버에 그대로 넘긴다
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "open_time")
    private LocalTime openTime;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "close_time")
    private LocalTime closeTime;

    /** 휴무 요일 mon,tue,... 빈 값이면 연중무휴 */
    @Column(name = "closed_days", nullable = false, length = 40)
    private String closedDays;

    // 위치 · 주소: 상인회가 관리(C6)
    @Column(name = "map_x", precision = 7, scale = 4)
    private BigDecimal mapX;

    @Column(name = "map_y", precision = 7, scale = 4)
    private BigDecimal mapY;

    @Column(name = "real_lat", precision = 9, scale = 6)
    private BigDecimal realLat;

    @Column(name = "real_lng", precision = 9, scale = 6)
    private BigDecimal realLng;

    @Column(name = "real_verified", nullable = false, insertable = false)
    private boolean realVerified;

    @Column(name = "address", length = 200)
    private String address;

    // 영업 상태 · 예약 받기: 사장님이 바꾼다(C22). 새 가게는 DB 기본값(open · 받는 중)
    @Column(name = "business_status", nullable = false, length = 20, insertable = false)
    private String businessStatus;

    @Column(name = "reservation_enabled", nullable = false, insertable = false)
    private boolean reservationEnabled;

    /** 영업 상태가 바뀐 시각. 쿠폰 30일 제한 해제가 이 시각 전 다운로드를 뺀다(06-data COUPON) */
    @Column(name = "business_status_changed_at")
    private Instant businessStatusChangedAt;

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

    void moveTo(String address, String alley, BigDecimal mapX, BigDecimal mapY) {
        this.address = address;
        this.alley = alley;
        this.mapX = mapX;
        this.mapY = mapY;
    }

    /** 사장님이 영업 상태 · 예약 받기를 바꾼다(C22). 영업 상태가 실제로 바뀌었으면 true. */
    boolean changeStatus(String businessStatus, boolean reservationEnabled, Instant now) {
        this.reservationEnabled = reservationEnabled;
        if (businessStatus.equals(this.businessStatus)) {
            return false;
        }
        this.businessStatus = businessStatus;
        this.businessStatusChangedAt = now;
        return true;
    }

    /** 사장님이 영업시간 · 휴무일을 바꾼다(C22). 예약 가능 시간(D5)이 이 칸을 읽는다. */
    void changeHours(LocalTime openTime, LocalTime closeTime, String closedDays) {
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.closedDays = closedDays;
    }

    /** 지금 예약을 받는 가게: 영업 중이고 예약 받기가 켜져 있음(FR-STO-02). 저장하지 않고 계산한다. */
    boolean isReservable() {
        return BUSINESS_OPEN.equals(businessStatus) && reservationEnabled;
    }

    private void applyInfo(StoreJoinInfo info) {
        name = info.name().strip();
        category = info.category();
        alley = info.alley();
        changeHours(info.openTime(), info.closeTime(), info.closedDaysValue());
    }
}
