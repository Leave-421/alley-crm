package com.alleycrm.seed;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * ⑦ 가상 데이터 2의 쿠폰 시연 사례(E17 #279, NFR-08 #5): {@link DemoCouponPlanner}의 계획을 가게 쿠폰 행사 · 출처 노출 · 쿠폰으로
 * 넣는다. 행사의 발급 수는 넣은 쿠폰 수와 같다. 쿠폰 · 노출은 {@code data_source = 'demo'}이고 시각 칸은 UTC 값이다.
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(DemoDataProperties.class)
class DemoCouponSeeder {

    static final String DEMO = DemoReservationSeeder.DEMO;

    private final JdbcTemplate jdbc;
    private final DemoDataProperties properties;

    DemoCouponSeeder(JdbcTemplate jdbc, DemoDataProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 넣은 행사 · 쿠폰 수와 사례별 쿠폰 수. */
    record Result(int events, int coupons, Map<DemoCouponPlanner.Case, Long> byCase, int blockedRevisits) {
    }

    Result insert(SeedRegionData data, RegionStoreSeeder.Result regionStores, DemoReservationSeeder.Result reservations,
            DemoCourseSeeder.Result courses) {
        Map<String, Long> storeIds = regionStores.storeIdsByCode();
        DemoCouponPlanner.Plan plan = DemoCouponPlanner.plan(data.stores(), reservations.plan(), courses.exploration(),
                properties.randomSeed() + 2);

        Map<String, Long> issued = plan.coupons().stream()
                .collect(Collectors.groupingBy(DemoCouponPlanner.Coupon::eventKey, Collectors.counting()));
        Map<String, Long> eventIds = new HashMap<>();
        for (DemoCouponPlanner.Event e : plan.events()) {
            eventIds.put(e.key(), insertEvent(e, storeId(storeIds, e.storeCode()), issued.getOrDefault(e.key(), 0L)));
        }
        Map<String, Long> exposureIds = new HashMap<>();
        for (DemoCouponPlanner.SourceExposure e : plan.exposures()) {
            exposureIds.put(e.key(), insertExposure(e, courses.visitorIds(), storeIds));
        }
        plan.blockedRevisits().forEach(e -> insertExposure(e, courses.visitorIds(), storeIds));

        Map<String, DemoCouponPlanner.Event> events = plan.events().stream()
                .collect(Collectors.toMap(DemoCouponPlanner.Event::key, Function.identity()));
        List<Object[]> rows = plan.coupons().stream().map(c -> new Object[] {
                reservations.customerIds().get(c.customerIndex()), eventIds.get(c.eventKey()),
                storeId(storeIds, events.get(c.eventKey()).storeCode()), storeId(storeIds, c.baseStoreCode()),
                exposureIds.get(c.exposureKey()), utc(c.downloadedAt()), utc(c.expiresAt()), c.status(),
                c.reservationNo() == null ? null : reservations.reservationIds().get(c.reservationNo()),
                c.discountApplied(), utc(c.usedAt()), DEMO}).toList();
        jdbc.batchUpdate("""
                INSERT INTO coupon (customer_id, issuer_kind, coupon_policy_id, target_store_id, source_base_store_id,
                                    source_exposure_id, downloaded_at, expires_at, status, reservation_id, discount_applied,
                                    used_at, data_source)
                VALUES (?, 'store', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);

        Map<DemoCouponPlanner.Case, Long> byCase = plan.coupons().stream()
                .collect(Collectors.groupingBy(DemoCouponPlanner.Coupon::kind, Collectors.counting()));
        return new Result(plan.events().size(), plan.coupons().size(), byCase, plan.blockedRevisits().size());
    }

    private long insertEvent(DemoCouponPlanner.Event e, long storeId, long issuedCount) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, discount_rate, max_discount_amount,
                                               service_name, service_value, valid_days, total_quantity, issued_count,
                                               confirmed_at, stopped_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, storeId);
            ps.setString(2, e.benefitType());
            ps.setObject(3, e.discountAmount());
            ps.setObject(4, e.discountPercent());
            ps.setObject(5, e.maxDiscountAmount());
            ps.setString(6, e.serviceName());
            ps.setObject(7, e.serviceValue());
            ps.setInt(8, e.validDays());
            ps.setInt(9, e.totalQuantity());
            ps.setLong(10, issuedCount);
            ps.setObject(11, utc(e.confirmedAt()));
            ps.setObject(12, utc(e.stoppedAt()));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** 쿠폰 출처 노출 · 중복 차단 노출. 한 묶음의 첫 칸이라 page 0, 지역 인기 가게 배지. */
    private long insertExposure(DemoCouponPlanner.SourceExposure e, Map<Integer, Long> visitorIds, Map<String, Long> storeIds) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`,
                                                         page, badge, reason_type, shown_at, data_source)
                    VALUES (?, ?, ?, ?, ?, 0, 'local_popular', 'attractiveness', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, visitorIds.get(e.visitorIndex()));
            ps.setString(2, e.displayId());
            ps.setLong(3, storeId(storeIds, e.baseStoreCode()));
            ps.setLong(4, storeId(storeIds, e.recommendedStoreCode()));
            ps.setInt(5, e.rank());
            ps.setObject(6, utc(e.shownAt()));
            ps.setString(7, DEMO);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private static long storeId(Map<String, Long> storeIds, String code) {
        Long id = storeIds.get(code);
        if (id == null) {
            throw new IllegalStateException("가상 데이터의 가게가 시드에 없습니다: " + code);
        }
        return id;
    }

    /** DB의 DATETIME 칸은 UTC 시각 그대로다(JdbcTemplate은 LocalDateTime을 바꾸지 않는다). */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
