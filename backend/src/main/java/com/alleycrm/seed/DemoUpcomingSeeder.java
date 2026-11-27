package com.alleycrm.seed;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ⑧ 다가올 예약 · 방문 신고(#315): {@link DemoUpcomingPlanner}의 계획을 예약 · 방문 신고 표에 넣는다.
 * 예약은 {@code data_source = 'demo'}이고(방문 신고 표에는 그 칸이 없다) 시각 칸은 UTC 값이다.
 * 지난 기록(⑤)과 같이 예약금은 예약 칸에만 남기고 가상 잔액 거래는 만들지 않는다. 소개 · 쿠폰 · 알림은 만들지 않는다.
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(DemoDataProperties.class)
class DemoUpcomingSeeder {

    static final String DEMO = DemoReservationSeeder.DEMO;

    private final JdbcTemplate jdbc;
    private final DemoDataProperties properties;

    DemoUpcomingSeeder(JdbcTemplate jdbc, DemoDataProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 넣은 다가올 예약 수(신청 · 확정 · 그중 변경 신청 · 지연 입력)와 방문 신고 수. */
    record Result(int reservations, int requested, int confirmed, int changeRequests, int delays, int reports) {
    }

    /** @param now 시드를 돌린 시각(기간의 오늘과 같은 날) */
    Result insert(SeedRegionData data, RegionStoreSeeder.Result regionStores, DemoReservationSeeder.Result reservations,
            Instant now) {
        DemoUpcomingPlanner.Plan plan = DemoUpcomingPlanner.plan(data.stores(), reservations.plan(), now,
                properties.randomSeed() + 3);
        insertReservations(plan.reservations(), reservations.customerIds(), regionStores.storeIdsByCode());
        insertReports(plan.reports(), reservations.customerIds(), reservations.reservationIds());

        List<DemoUpcomingPlanner.Upcoming> rows = plan.reservations();
        int requested = (int) rows.stream().filter(u -> DemoUpcomingPlanner.REQUESTED.equals(u.status())).count();
        int changes = (int) rows.stream().filter(DemoUpcomingPlanner.Upcoming::changeRequested).count();
        int delays = (int) rows.stream().filter(u -> u.delayEta() != null).count();
        return new Result(rows.size(), requested, rows.size() - requested, changes, delays, plan.reports().size());
    }

    private void insertReservations(List<DemoUpcomingPlanner.Upcoming> upcoming, Map<Integer, Long> customerIds,
            Map<String, Long> storeIds) {
        List<Object[]> rows = upcoming.stream().map(u -> new Object[] {
                u.reservationNo(), storeId(storeIds, u.storeCode()), customerIds.get(u.customerIndex()), utc(u.startAt()),
                u.partySize(), u.status(), utc(u.requestedAt()), utc(u.confirmedAt()), utc(u.pendingStartAt()),
                u.pendingPartySize(), u.depositAmount(), utc(u.delayEta()), DEMO}).toList();
        jdbc.batchUpdate("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at,
                                         confirmed_at, pending_start_at, pending_party_size, deposit_amount, delay_eta,
                                         data_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private void insertReports(List<DemoUpcomingPlanner.Report> reports, Map<Integer, Long> customerIds,
            Map<String, Long> reservationIds) {
        List<Object[]> rows = reports.stream().map(r -> {
            Long reservationId = reservationIds.get(r.reservationNo());
            if (reservationId == null) {
                throw new IllegalStateException("방문 신고의 예약이 없습니다: " + r.reservationNo());
            }
            return new Object[] {reservationId, customerIds.get(r.customerIndex()), r.type(), r.claimedAmount(), r.status(),
                    utc(r.createdAt())};
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO visit_report (reservation_id, customer_id, type, claimed_amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private static long storeId(Map<String, Long> storeIds, String code) {
        Long id = storeIds.get(code);
        if (id == null) {
            throw new IllegalStateException("다가올 예약의 가게가 시드에 없습니다: " + code);
        }
        return id;
    }

    /** DB의 DATETIME 칸은 UTC 시각 그대로다(JdbcTemplate은 LocalDateTime을 바꾸지 않는다). */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
