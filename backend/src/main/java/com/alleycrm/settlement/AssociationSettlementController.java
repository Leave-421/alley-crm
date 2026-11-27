package com.alleycrm.settlement;

import static com.alleycrm.common.time.ServiceTime.instant;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.ServiceTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상인회 월 정산(D17, FR-STL-05, A-04). 정산 달 = 방문 처리된 달(D15가 수수료에 남김). 방문 전에 취소된 예약은 수수료가
 * 없으므로 자동으로 빠진다. 확정은 확인 기록이며 잔액 · 수수료를 바꾸지 않는다(11-24).
 * 권역은 로그인한 상인회 계정으로 정한다. 가게 단위 묶음 숫자만 보여주고 손님 정보는 없다(FR-ANL-05).
 */
@RestController
class AssociationSettlementController {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AssociationSettlementController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/association/settlements/{month}")
    Map<String, Object> get(@PathVariable String month, Authentication auth) {
        long regionId = regionOf(auth);
        String m = month(month);
        List<StoreRow> stores = jdbc.query("""
                SELECT s.id, s.name, COUNT(*) AS cnt, SUM(f.fee_amount) AS fee, ROUND(AVG(f.fee_base)) AS avg_base,
                       SUM(f.coupon_cost) AS coupon_cost
                FROM referral_fee f JOIN store s ON s.id = f.receiving_store_id
                WHERE s.region_id = ? AND f.settlement_month = ?
                GROUP BY s.id, s.name
                ORDER BY s.name, s.id
                """, (rs, i) -> new StoreRow(
                String.valueOf(rs.getLong("id")),
                rs.getString("name"),
                rs.getInt("cnt"),
                rs.getLong("fee"),
                rs.getLong("avg_base"),
                rs.getLong("coupon_cost")), regionId, m);
        List<Map<String, Object>> row = jdbc.queryForList(
                "SELECT status, confirmed_at FROM settlement WHERE region_id = ? AND month = ?", regionId, m);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", m);
        out.put("status", row.isEmpty() ? "aggregated" : row.get(0).get("status"));
        out.put("confirmedAt", row.isEmpty() ? null : instant((LocalDateTime) row.get(0).get("confirmed_at")));
        out.put("canConfirm", row.isEmpty() && monthEnded(m) && unprocessedReports(regionId, m) == 0);
        out.put("unprocessedReportCount", unprocessedReports(regionId, m));
        out.put("count", stores.stream().mapToInt(StoreRow::count).sum());
        out.put("totalFee", stores.stream().mapToLong(StoreRow::totalFee).sum());
        out.put("stores", stores);
        return out;
    }

    /**
     * 확정(FR-STL-05 #4): 그 달이 끝났고, 처리되지 않은 신고가 없어야 한다(#5, 11-25 (5)). 이미 확정이면 conflict.
     * 그 달 수수료 행에 정산 id를 단다. 잔액은 바꾸지 않는다.
     */
    @PostMapping("/api/v1/association/settlements/{month}/confirm")
    @Transactional
    Map<String, Object> confirm(@PathVariable String month, Authentication auth) {
        long regionId = regionOf(auth);
        String m = month(month);
        if (!monthEnded(m) || unprocessedReports(regionId, m) > 0) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        // 같은 달을 동시에 확정해도 uk_settlement_month가 한 행만 남긴다
        int inserted = jdbc.update("""
                INSERT IGNORE INTO settlement (region_id, month, status, confirmed_at) VALUES (?, ?, 'confirmed', ?)
                """, regionId, m, now);
        if (inserted == 0) {
            throw new ApiException(ErrorCode.CONFLICT); // 이미 확정
        }
        long settlementId = jdbc.queryForObject("SELECT id FROM settlement WHERE region_id = ? AND month = ?",
                Long.class, regionId, m);
        jdbc.update("""
                UPDATE referral_fee f JOIN store s ON s.id = f.receiving_store_id
                SET f.settlement_id = ?
                WHERE s.region_id = ? AND f.settlement_month = ?
                """, settlementId, regionId, m);
        return get(m, auth);
    }

    /** 그 달(예약 시작 달, 운영 시간대) 예약에 걸린, 처리 완료되지 않은 금액 · 미처리 신고 수 */
    private int unprocessedReports(long regionId, String month) {
        YearMonth ym = YearMonth.parse(month);
        LocalDateTime from = LocalDateTime.ofInstant(ym.atDay(1).atStartOfDay(ServiceTime.ZONE).toInstant(), ZoneOffset.UTC);
        LocalDateTime to = LocalDateTime.ofInstant(ym.plusMonths(1).atDay(1).atStartOfDay(ServiceTime.ZONE).toInstant(), ZoneOffset.UTC);
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM visit_report v
                JOIN reservation r ON r.id = v.reservation_id
                JOIN store s ON s.id = r.store_id
                WHERE s.region_id = ? AND v.status <> 'done' AND r.start_at >= ? AND r.start_at < ?
                """, Integer.class, regionId, from, to);
        return n == null ? 0 : n;
    }

    private boolean monthEnded(String month) {
        YearMonth now = YearMonth.from(Instant.now(clock).atZone(ServiceTime.ZONE));
        return YearMonth.parse(month).isBefore(now);
    }

    private long regionOf(Authentication auth) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, region_id FROM account WHERE login_id = ?", auth.getName());
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Map<String, Object> a = rows.get(0);
        if (!"association".equals(a.get("role")) || a.get("region_id") == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return ((Number) a.get("region_id")).longValue();
    }

    /** 가게별 정산 한 줄. avgVisitAmount = 소개 건 평균 방문 금액(쿠폰 할인 후, 원 단위 반올림). 낮은지 판단하지 않는다 */
    record StoreRow(String storeId, String storeName, int count, long totalFee, long avgVisitAmount, long couponCost) {
    }

    private static String month(String value) {
        try {
            return YearMonth.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }
}
