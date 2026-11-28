package com.alleycrm.seed;

import com.alleycrm.notification.NotificationType;
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
 * 지난 기록(⑤)과 같이 예약금은 예약 칸에만 남기고 가상 잔액 거래는 만들지 않는다. 소개 · 쿠폰은 만들지 않는다.
 * 알림은 사장님 확인 요청 신고의 사장님 알림만 넣는다({@link #notifyOwnersOfCheckRequests}).
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(DemoDataProperties.class)
class DemoUpcomingSeeder {

    static final String DEMO = DemoReservationSeeder.DEMO;
    /** notification.recipient_type의 계정 값(C14 NotificationType은 패키지 안에서만 보인다). */
    static final String RECIPIENT_ACCOUNT = "account";

    private final JdbcTemplate jdbc;
    private final DemoDataProperties properties;

    DemoUpcomingSeeder(JdbcTemplate jdbc, DemoDataProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 넣은 다가올 예약 수(신청 · 확정 · 그중 변경 신청 · 지연 입력), 방문 신고 수, 신고 확인 요청 사장님 알림 수. */
    record Result(int reservations, int requested, int confirmed, int changeRequests, int delays, int reports,
            int ownerNotifications) {
    }

    /** @param now 시드를 돌린 시각(기간의 오늘과 같은 날) */
    Result insert(SeedRegionData data, RegionStoreSeeder.Result regionStores, DemoReservationSeeder.Result reservations,
            Instant now) {
        DemoUpcomingPlanner.Plan plan = DemoUpcomingPlanner.plan(data.stores(), reservations.plan(), now,
                properties.randomSeed() + 3);
        insertReservations(plan.reservations(), reservations.customerIds(), regionStores.storeIdsByCode());
        insertReports(plan.reports(), reservations.customerIds(), reservations.reservationIds());
        int notified = notifyOwnersOfCheckRequests(now);

        List<DemoUpcomingPlanner.Upcoming> rows = plan.reservations();
        int requested = (int) rows.stream().filter(u -> DemoUpcomingPlanner.REQUESTED.equals(u.status())).count();
        int changes = (int) rows.stream().filter(DemoUpcomingPlanner.Upcoming::changeRequested).count();
        int delays = (int) rows.stream().filter(u -> u.delayEta() != null).count();
        return new Result(rows.size(), requested, rows.size() - requested, changes, delays, plan.reports().size(),
                notified);
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

    /**
     * 사장님 확인 요청 신고마다 그 가게 사장님에게 {@code visit_report_check_requested} 알림 한 행(C23 · C14
     * {@code VisitReportNotificationListener}와 같은 칸 · payload). 사장님 계정이 있는 가게(시연 사장님 S01)만 생긴다.
     * 상인회가 요청한 시각은 신고 30분 뒤로 두되 지금을 넘지 않는다. 읽지 않은 상태다.
     *
     * @return 넣은 알림 수
     */
    int notifyOwnersOfCheckRequests(Instant now) {
        return jdbc.update("""
                INSERT INTO notification (recipient_type, recipient_id, type, payload, created_at)
                SELECT ?, a.id, ?,
                       JSON_OBJECT('visitReportId', CAST(v.id AS CHAR), 'reservationId', CAST(v.reservation_id AS CHAR)),
                       LEAST(v.created_at + INTERVAL 30 MINUTE, ?)
                FROM visit_report v
                JOIN reservation r ON r.id = v.reservation_id
                JOIN account a ON a.store_id = r.store_id AND a.role = 'owner'
                WHERE v.status = ? AND r.data_source = ?
                ORDER BY v.id
                """, RECIPIENT_ACCOUNT, NotificationType.VISIT_REPORT_CHECK_REQUESTED, utc(now),
                DemoUpcomingPlanner.REPORT_OWNER_CHECK_REQUESTED, DEMO);
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
