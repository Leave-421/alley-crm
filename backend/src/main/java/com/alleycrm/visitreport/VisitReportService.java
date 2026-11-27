package com.alleycrm.visitreport;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.reservation.CancelReason;
import com.alleycrm.reservation.ReservationStatus;
import com.alleycrm.store.StoreService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 신고(FR-STL-08): 회원 신고 → 상인회 사장님 확인 요청 → 사장님 답변 → 상인회 결과 기록.
 * 예약 · 가게 · 정산은 다른 도메인 테이블이라 읽기만 한다. 결과 '맞음' 뒤의 정정 · 늦은 방문 처리는 D22 · D23이 한다.
 */
@Service
public class VisitReportService {

    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final VisitReportRepository reports;
    private final CustomerService customers;
    private final StoreService stores;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    VisitReportService(VisitReportRepository reports, CustomerService customers, StoreService stores, JdbcTemplate jdbc,
                       Clock clock, ApplicationEventPublisher events) {
        this.reports = reports;
        this.customers = customers;
        this.stores = stores;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /**
     * 회원 신고(FR-STL-08 #1 ~ #3). 남의 예약은 404.
     * 금액 신고는 방문 처리된 예약만, 미처리 신고는 자동 취소된 예약만. 그 달 정산이 확정됐거나 같은 종류 신고가 있으면 409.
     */
    @Transactional
    public VisitReport create(AccountPrincipal me, long reservationId, String type, Integer claimedAmount) {
        me.requireRole(AccountRole.CUSTOMER);
        long customerId = customers.identifyMember(me.id());
        // 같은 예약에 동시에 신고해도 한 건만 남도록 예약 행을 잠근다
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.customer_id, r.status, r.cancel_reason, r.start_at, s.region_id
                FROM reservation r JOIN store s ON s.id = r.store_id
                WHERE r.id = ? FOR UPDATE
                """, reservationId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("customer_id")).longValue() != customerId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);
        boolean reportable = VisitReport.TYPE_AMOUNT.equals(type)
                ? ReservationStatus.VISITED.equals(r.get("status"))
                : ReservationStatus.CANCELED.equals(r.get("status")) && CancelReason.AUTO_CANCELED.equals(r.get("cancel_reason"));
        if (!reportable) {
            throw new ApiException(ErrorCode.CONFLICT, VisitReport.TYPE_AMOUNT.equals(type)
                    ? "방문 처리된 예약만 금액을 신고할 수 있습니다." : "자동 취소된 예약만 '방문했어요'를 신고할 수 있습니다.");
        }
        // shortcut: 정산 확정과 동시에 들어온 신고는 막지 못할 수 있다(확정 쪽은 신고 수를 보고 확정한다), 문제가 되면 settlement 행을 잠근다
        if (monthConfirmed(((Number) r.get("region_id")).longValue(), month((LocalDateTime) r.get("start_at")))) {
            throw new ApiException(ErrorCode.CONFLICT, "그 달 정산이 확정돼 신고를 받을 수 없습니다.");
        }
        if (reports.existsByReservationIdAndType(reservationId, type)) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 신고한 예약입니다.");
        }
        Integer amount = VisitReport.TYPE_AMOUNT.equals(type) ? claimedAmount : null;
        return reports.save(VisitReport.received(reservationId, customerId, type, amount, now()));
    }

    /** 회원의 내 신고, 최근 것부터 */
    @Transactional(readOnly = true)
    public List<VisitReport> mine(AccountPrincipal me) {
        me.requireRole(AccountRole.CUSTOMER);
        long customerId = customers.identifyMember(me.id());
        return reports.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId);
    }

    /** 상인회: 자기 권역 가게 예약의 신고, 오래된 것부터. status가 null이면 전부 */
    @Transactional(readOnly = true)
    public Page associationPage(AccountPrincipal me, String status, int page, int size) {
        me.requireRole(AccountRole.ASSOCIATION);
        return page("s.region_id = ?", me.regionId(), status, false, page, size);
    }

    /** 사장님: 내 가게 예약의 신고 중 확인 요청을 받은 것(확인 요청 · 처리 완료), 최근 것부터 */
    @Transactional(readOnly = true)
    public Page ownerPage(AccountPrincipal me, int page, int size) {
        long storeId = stores.requireApprovedOwnStore(me);
        return page("r.store_id = ? AND v.status <> '" + VisitReport.STATUS_RECEIVED + "'", storeId, null, true, page, size);
    }

    /** 상인회: 신고 한 건(목록과 같은 모양). 다른 권역은 404 */
    @Transactional(readOnly = true)
    public VisitReportResponses.Detail associationDetail(AccountPrincipal me, long reportId) {
        me.requireRole(AccountRole.ASSOCIATION);
        return detail("s.region_id = ?", me.regionId(), reportId);
    }

    /** 사장님: 내 가게 신고 한 건. 다른 가게는 404 */
    @Transactional(readOnly = true)
    public VisitReportResponses.Detail ownerDetail(AccountPrincipal me, long reportId) {
        return detail("r.store_id = ?", stores.requireApprovedOwnStore(me), reportId);
    }

    /** 상인회: 접수된 신고를 그 가게 사장님에게 확인 요청한다. 커밋 뒤 사장님 알림 */
    @Transactional
    public VisitReport requestOwnerCheck(AccountPrincipal me, long reportId) {
        me.requireRole(AccountRole.ASSOCIATION);
        VisitReport report = lockInRegion(reportId, me.regionId());
        if (!VisitReport.STATUS_RECEIVED.equals(report.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "접수 상태인 신고만 확인을 요청할 수 있습니다.");
        }
        report.requestOwnerCheck();
        long storeId = jdbc.queryForObject("SELECT store_id FROM reservation WHERE id = ?", Long.class, report.getReservationId());
        events.publishEvent(new VisitReportOwnerCheckRequestedEvent(report.getId(), report.getReservationId(), storeId));
        return report;
    }

    /** 사장님: 확인 요청받은 신고에 답한다. 다시 답하면 고쳐 쓴다. 다른 가게 신고는 404 */
    @Transactional
    public VisitReport reply(AccountPrincipal me, long reportId, String reply) {
        long storeId = stores.requireApprovedOwnStore(me);
        VisitReport report = lock(reportId, "r.store_id = ?", storeId);
        if (!VisitReport.STATUS_OWNER_CHECK_REQUESTED.equals(report.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "확인 요청 중인 신고에만 답할 수 있습니다.");
        }
        report.reply(reply.strip(), now());
        return report;
    }

    /** 상인회: 확인 요청한 신고의 결과(맞음 · 아님)를 기록한다(FR-STL-08 #4). 금액 · 예약은 바꾸지 않고, 페널티도 없다 */
    @Transactional
    public VisitReport recordResult(AccountPrincipal me, long reportId, String result) {
        me.requireRole(AccountRole.ASSOCIATION);
        VisitReport report = lockInRegion(reportId, me.regionId());
        if (!VisitReport.STATUS_OWNER_CHECK_REQUESTED.equals(report.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "사장님에게 확인을 요청한 신고만 결과를 기록할 수 있습니다.");
        }
        report.decide(result, now());
        return report;
    }

    /** D22 금액 정정 · D23 늦은 방문 처리가 쓴다: 그 예약에 상인회가 '맞음'으로 기록한 그 종류 신고가 있는지 */
    @Transactional(readOnly = true)
    public boolean hasValidReport(long reservationId, String type) {
        return reports.existsByReservationIdAndTypeAndResult(reservationId, type, VisitReport.RESULT_VALID);
    }

    private VisitReport lockInRegion(long reportId, Long regionId) {
        return lock(reportId, "s.region_id = ?", regionId);
    }

    /** 조건(권역 · 가게)에 맞는 신고만 잠가 읽는다. 아니면 존재를 숨기고 404 */
    private VisitReport lock(long reportId, String scope, Object scopeValue) {
        List<Long> ids = jdbc.queryForList("SELECT v.id " + FROM_JOINS + "WHERE v.id = ? AND " + scope + " FOR UPDATE",
                Long.class, reportId, scopeValue);
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return reports.findById(reportId).orElseThrow();
    }

    /** scope는 이 클래스 안의 고정 문자열뿐이고 값은 모두 바인딩한다 */
    private Page page(String scope, Object scopeValue, String status, boolean newestFirst, int page, int size) {
        List<Object> args = new ArrayList<>(List.of(scopeValue));
        String where = scope;
        if (status != null) {
            where += " AND v.status = ?";
            args.add(status);
        }
        String from = FROM_JOINS + "WHERE " + where;
        long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        String order = newestFirst ? "v.created_at DESC, v.id DESC" : "v.created_at, v.id";
        List<VisitReportResponses.Detail> items = jdbc.query(DETAIL_SELECT + from + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                detailMapper(), args.toArray());
        return new Page(items, total);
    }

    private VisitReportResponses.Detail detail(String scope, Object scopeValue, long reportId) {
        List<VisitReportResponses.Detail> rows = jdbc.query(DETAIL_SELECT + FROM_JOINS + "WHERE v.id = ? AND " + scope,
                detailMapper(), reportId, scopeValue);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static final String FROM_JOINS = """
            FROM visit_report v
            JOIN reservation r ON r.id = v.reservation_id JOIN store s ON s.id = r.store_id
            """;
    private static final String DETAIL_SELECT = """
            SELECT v.id, v.type, v.status, v.result, v.claimed_amount, v.owner_reply, v.owner_replied_at,
                   v.created_at, v.handled_at, s.id AS store_id, s.name AS store_name,
                   r.id AS reservation_id, r.reservation_no, r.start_at, r.visit_amount
            """;

    private static RowMapper<VisitReportResponses.Detail> detailMapper() {
        return (rs, i) -> {
            LocalDateTime startAt = rs.getObject("start_at", LocalDateTime.class);
            return new VisitReportResponses.Detail(
                    String.valueOf(rs.getLong("id")), rs.getString("type"), rs.getString("status"), rs.getString("result"),
                    String.valueOf(rs.getLong("store_id")), rs.getString("store_name"),
                    String.valueOf(rs.getLong("reservation_id")), rs.getString("reservation_no"),
                    instant(startAt), month(startAt),
                    (Integer) rs.getObject("visit_amount"), (Integer) rs.getObject("claimed_amount"),
                    rs.getString("owner_reply"), instant(rs.getObject("owner_replied_at", LocalDateTime.class)),
                    instant(rs.getObject("created_at", LocalDateTime.class)),
                    instant(rs.getObject("handled_at", LocalDateTime.class)));
        };
    }

    private boolean monthConfirmed(long regionId, String month) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE region_id = ? AND month = ? AND status = 'confirmed'",
                Integer.class, regionId, month);
        return n != null && n > 0;
    }

    /** 정산 달 = 예약 시작 달(운영 시간대), 월 정산(D17)과 같은 기준. DB 시각은 UTC 벽시계 */
    static String month(LocalDateTime startAtUtc) {
        return YearMonth.from(startAtUtc.toInstant(ZoneOffset.UTC).atZone(SERVICE_ZONE)).toString();
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    record Page(List<VisitReportResponses.Detail> items, long totalElements) {
    }
}
