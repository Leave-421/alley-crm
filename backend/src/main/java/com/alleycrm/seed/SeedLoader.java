package com.alleycrm.seed;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 · 시연 데이터를 한 트랜잭션에서 지우고 다시 넣는다. 중간에 실패하면 지운 것까지 모두 롤백된다.
 * 같은 DB에 두 실행기가 동시에 돌지 않도록 MySQL 이름 잠금을 잡는다.
 *
 * <p>넣는 순서: ① 초기화 → ② 권역 · 가게(E1) → ③ 시연용 고정 계정(#141, {@link DemoAccountSeeder})
 * → ④ 공공데이터 기본값(E2, {@link PublicStatSeeder}) → ⑤ 가상 데이터 1: 손님 · 예약 · 취소 · 방문(E16, {@link DemoReservationSeeder}).
 * → ⑥ 가상 데이터 2: 방문자 · 노출 · 정식 코스 제안 · 코스 연결 · 소개 수수료 · 정산(E17, {@link DemoCourseSeeder})
 * → ⑦ 쿠폰 시연 사례(E17, {@link DemoCouponSeeder}) → ⑧ 다가올 예약 · 방문 신고(#315, {@link DemoUpcomingSeeder}).
 *
 * <p>가상 데이터의 기간은 서버 시계({@link Clock} 빈, 시연에서 앞당긴 시각 포함)로 정한다: 오늘 = 지금의 운영 시간대 날짜,
 * 지난 기록은 어제까지 91일({@link DemoPeriod}), 다가올 예약은 오늘부터.
 */
@Component
@Profile("seed")
class SeedLoader {

    static final String LOCK_NAME = "alley_crm_seed";

    private final JdbcTemplate jdbc;
    private final SeedTableCleaner cleaner;
    private final RegionStoreSeeder regionStores;
    private final DemoAccountSeeder demoAccounts;
    private final PublicStatSeeder publicStats;
    private final DemoReservationSeeder demoReservations;
    private final DemoCourseSeeder demoCourses;
    private final DemoCouponSeeder demoCoupons;
    private final DemoUpcomingSeeder demoUpcoming;
    private final Clock clock;

    SeedLoader(JdbcTemplate jdbc, SeedTableCleaner cleaner, RegionStoreSeeder regionStores, DemoAccountSeeder demoAccounts,
            PublicStatSeeder publicStats, DemoReservationSeeder demoReservations, DemoCourseSeeder demoCourses,
            DemoCouponSeeder demoCoupons, DemoUpcomingSeeder demoUpcoming, Clock clock) {
        this.jdbc = jdbc;
        this.cleaner = cleaner;
        this.regionStores = regionStores;
        this.demoAccounts = demoAccounts;
        this.publicStats = publicStats;
        this.demoReservations = demoReservations;
        this.demoCourses = demoCourses;
        this.demoCoupons = demoCoupons;
        this.demoUpcoming = demoUpcoming;
        this.clock = clock;
    }

    record Result(List<String> clearedTables, RegionStoreSeeder.Result regionStores, List<String> demoAccounts,
            int publicStats, DemoReservationSeeder.Result demoReservations, DemoCourseSeeder.Result demoCourses,
            DemoCouponSeeder.Result demoCoupons, DemoPeriod period, DemoUpcomingSeeder.Result demoUpcoming) {
    }

    @Transactional
    public Result resetAndLoad(SeedRegionData regionStoreData) {
        acquireLock();
        try {
            Instant now = clock.instant();
            DemoPeriod period = DemoPeriod.at(now);
            List<String> cleared = cleaner.deleteAll();
            RegionStoreSeeder.Result stores = regionStores.insert(regionStoreData);
            List<String> accounts = demoAccounts.insert(stores);
            int stats = publicStats.insert(PublicStatFile.load());
            DemoReservationSeeder.Result reservations = demoReservations.insert(regionStoreData, stores, period);
            DemoCourseSeeder.Result courses = demoCourses.insert(regionStoreData, stores, reservations);
            DemoCouponSeeder.Result coupons = demoCoupons.insert(regionStoreData, stores, reservations, courses);
            DemoUpcomingSeeder.Result upcoming = demoUpcoming.insert(regionStoreData, stores, reservations, now);
            return new Result(cleared, stores, accounts, stats, reservations, courses, coupons, period, upcoming);
        } finally {
            releaseLock();
        }
    }

    /** 트랜잭션과 같은 연결에서 잡는다. 이미 다른 연결이 잡고 있으면 기다리지 않고 실패한다. */
    private void acquireLock() {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, LOCK_NAME);
        if (acquired == null || acquired != 1) {
            throw new IllegalStateException("다른 시드 실행이 같은 DB에서 진행 중입니다. 끝난 뒤 다시 실행하세요.");
        }
    }

    private void releaseLock() {
        jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
    }
}
