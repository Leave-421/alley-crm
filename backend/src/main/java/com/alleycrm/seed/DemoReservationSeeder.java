package com.alleycrm.seed;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * ⑤ 가상 데이터 1(E16 #107): {@link DemoReservationPlanner}의 계획을 손님 · 예약 표에 넣는다.
 * 표 · 칸은 D1 · D19 마이그레이션 그대로 쓰고, 모든 행은 {@code data_source = 'demo'}(NFR-10)다.
 * 시각 칸은 UTC 값이다. 예약금 · 방문 금액은 예약 칸에만 남기고 가상 잔액 거래는 만들지 않는다.
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(DemoDataProperties.class)
class DemoReservationSeeder {

    static final String DEMO = "demo";

    private final JdbcTemplate jdbc;
    private final DemoDataProperties properties;

    DemoReservationSeeder(JdbcTemplate jdbc, DemoDataProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 넣은 손님 · 예약 수와 가상 데이터 2(E17)가 이어 쓸 계획 · ID.
     *
     * @param customerIds 계획의 손님 번호 → 손님 ID
     * @param reservationIds 예약번호 → 예약 ID
     */
    record Result(int customers, int reservations, int canceled, int visited, DemoReservationPlanner.Plan plan,
            Map<Integer, Long> customerIds, Map<String, Long> reservationIds) {
    }

    Result insert(SeedRegionData data, RegionStoreSeeder.Result regionStores) {
        DemoReservationPlanner.Plan plan = DemoReservationPlanner.plan(
                data.stores(), properties.customers(), properties.randomSeed());
        Map<Integer, List<DemoReservationPlanner.Reservation>> byCustomer = plan.reservations().stream()
                .collect(Collectors.groupingBy(DemoReservationPlanner.Reservation::customerIndex));
        Map<Integer, Long> customerIds = new HashMap<>();
        for (DemoReservationPlanner.Customer customer : plan.customers()) {
            customerIds.put(customer.index(),
                    insertCustomer(customer, byCustomer.getOrDefault(customer.index(), List.of())));
        }
        insertReservations(plan.reservations(), customerIds, regionStores.storeIdsByCode());
        Map<String, Long> reservationIds = new HashMap<>();
        jdbc.query("SELECT id, reservation_no FROM reservation WHERE data_source = ?",
                rs -> {
                    reservationIds.put(rs.getString("reservation_no"), rs.getLong("id"));
                }, DEMO);
        int canceled = (int) plan.reservations().stream().filter(r -> "canceled".equals(r.status())).count();
        return new Result(plan.customers().size(), plan.reservations().size(), canceled,
                plan.reservations().size() - canceled, plan, customerIds, reservationIds);
    }

    private long insertCustomer(DemoReservationPlanner.Customer c, List<DemoReservationPlanner.Reservation> mine) {
        LocalDateTime lastVisit = latest(mine, DemoReservationPlanner.Reservation::visitedAt);
        LocalDateTime lastCancel = latest(mine, DemoReservationPlanner.Reservation::canceledAt);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO customer (type, name, phone, gender, age_group, last_visit_at, last_cancel_at, data_source)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.type());
            ps.setString(2, c.name());
            ps.setString(3, c.phone());
            ps.setString(4, c.gender());
            ps.setString(5, c.ageGroup());
            ps.setObject(6, lastVisit);
            ps.setObject(7, lastCancel);
            ps.setString(8, DEMO);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private void insertReservations(List<DemoReservationPlanner.Reservation> reservations, Map<Integer, Long> customerIds,
            Map<String, Long> storeIds) {
        List<Object[]> rows = new ArrayList<>();
        for (DemoReservationPlanner.Reservation r : reservations) {
            Long storeId = storeIds.get(r.storeCode());
            if (storeId == null) {
                throw new IllegalStateException("가상 예약의 가게가 시드에 없습니다: " + r.storeCode());
            }
            rows.add(new Object[] {
                    r.reservationNo(), storeId, customerIds.get(r.customerIndex()), utc(r.startAt()), r.partySize(),
                    r.status(), r.cancelReason(), utc(r.requestedAt()), utc(r.confirmedAt()), utc(r.visitedAt()),
                    utc(r.canceledAt()), r.depositAmount(), r.visitAmount(), DEMO});
        }
        jdbc.batchUpdate("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, confirmed_at, visited_at, canceled_at, deposit_amount, visit_amount,
                                         data_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private static LocalDateTime latest(List<DemoReservationPlanner.Reservation> reservations,
            Function<DemoReservationPlanner.Reservation, Instant> at) {
        Optional<Instant> latest = reservations.stream().map(at).filter(t -> t != null).max(Comparator.naturalOrder());
        return latest.map(DemoReservationSeeder::utc).orElse(null);
    }

    /** DB의 DATETIME 칸은 UTC 시각 그대로다(JdbcTemplate은 LocalDateTime을 바꾸지 않는다). */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
