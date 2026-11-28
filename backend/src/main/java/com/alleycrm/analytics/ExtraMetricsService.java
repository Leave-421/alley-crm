package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.store.StoreService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추가 지표 · 쿠폰 지표(FR-ANL-08 · 09, C21). 범위는 사장님이면 내 가게, 상인회면 권역 전체다. 전체 기간.
 * 클릭 · 노출 · 예약 · 방문은 서로 다른 표에서 따로 센다(FR-ANL-08 #2). 보낸 · 받은 손님 수는 이미 있는 API를 쓴다
 * (사장님 D16 `referral-stats`, 상인회 C19 `store-performance`). 집계라 다른 도메인 표를 네이티브 SQL로 읽기만 한다.
 */
@Service
public class ExtraMetricsService {

    /** 범위 조건: 권역 안이고, 가게를 정했으면 그 가게(s는 가게 표 별칭). 매개변수: regionId, storeId, storeId */
    private static final String IN_SCOPE = "s.region_id = ? AND (? IS NULL OR s.id = ?)";

    private static final String FUNNEL = """
            SELECT (SELECT COUNT(*) FROM store_click c JOIN store s ON s.id = c.store_id WHERE %1$s) AS clicks,
                   (SELECT COUNT(*) FROM recommendation_exposure e JOIN store s ON s.id = e.recommended_store_id
                    WHERE %1$s) AS exposures,
                   (SELECT COUNT(*) FROM reservation r JOIN store s ON s.id = r.store_id WHERE %1$s) AS reservations,
                   (SELECT COUNT(*) FROM reservation r JOIN store s ON s.id = r.store_id
                    WHERE r.status = 'visited' AND %1$s) AS visits
            """.formatted(IN_SCOPE);

    /**
     * 추천 후 예약 전환: (방문자, 추천 가게) 쌍마다 처음 본 시각 뒤에, 그 방문자와 이어진 손님이 그 가게에 예약을 신청했는지.
     * shortcut: 기간 제한 없이 "본 뒤 언젠가 예약"이면 전환이다. 전환 기간이 정해지면 requested_at 조건에 더한다.
     */
    private static final String CONVERSION = """
            SELECT COUNT(*) AS pairs,
                   SUM(EXISTS (SELECT 1 FROM reservation r WHERE r.store_id = p.store_id AND r.customer_id = p.customer_id
                                                              AND r.requested_at >= p.first_shown)) AS converted
            FROM (
                SELECT e.visitor_id, e.recommended_store_id AS store_id, v.customer_id, MIN(e.shown_at) AS first_shown
                FROM recommendation_exposure e
                JOIN store s ON s.id = e.recommended_store_id
                JOIN visitor v ON v.id = e.visitor_id
                WHERE %s
                GROUP BY e.visitor_id, e.recommended_store_id, v.customer_id
            ) p
            """.formatted(IN_SCOPE);

    /**
     * 확인된 연계 방문(C20 방문 흐름과 같은 기준: 같은 손님이 같은 날 연달아 방문 처리한 서로 다른 두 가게).
     * 범위: 출발이나 도착 가게가 범위 안. 매개변수: regionId, (regionId, storeId, storeId) × 2
     */
    private static final String MOVES = """
            SELECT t.customer_id, fs.category AS from_category, ts.category AS to_category
            FROM (
                SELECT r.customer_id, r.store_id AS to_store,
                       LAG(r.store_id) OVER (PARTITION BY r.customer_id, DATE(DATE_ADD(r.start_at, INTERVAL 9 HOUR))
                                             ORDER BY r.start_at, r.id) AS from_store
                FROM reservation r JOIN store s ON s.id = r.store_id
                WHERE r.status = 'visited' AND s.region_id = ?
            ) t
            JOIN store fs ON fs.id = t.from_store
            JOIN store ts ON ts.id = t.to_store
            WHERE t.from_store <> t.to_store
              AND (%s OR %s)
            """.formatted(IN_SCOPE.replace("s.", "fs."), IN_SCOPE.replace("s.", "ts."));

    /** 시간대별 예약: 예약 시작 시각(운영 시간대)의 요일(1 = 월요일) · 시. 취소된 예약은 넣지 않는다(FR-ANL-07) */
    private static final String HOURLY = """
            SELECT WEEKDAY(DATE_ADD(r.start_at, INTERVAL 9 HOUR)) + 1 AS dow, HOUR(DATE_ADD(r.start_at, INTERVAL 9 HOUR)) AS hr,
                   COUNT(*) AS cnt, COUNT(DISTINCT r.customer_id) AS people
            FROM reservation r JOIN store s ON s.id = r.store_id
            WHERE r.status <> 'canceled' AND %s
            GROUP BY dow, hr
            ORDER BY dow, hr
            """.formatted(IN_SCOPE);

    /**
     * 쿠폰 지표. 쿠폰 한 장 = 발급 1건이라 같은 쿠폰을 여러 번 눌러도 다운로드 수는 늘지 않는다(FR-ANL-09 #1).
     * 예약 적용 = 한 번이라도 예약에 적용된 쿠폰(적용 뒤 취소 · 사용 포함).
     */
    private static final String COUPONS = """
            SELECT COUNT(*) AS downloads, COUNT(DISTINCT c.customer_id) AS people,
                   SUM(c.reservation_id IS NOT NULL) AS applied,
                   SUM(c.status = 'expired') AS expired,
                   SUM(r.status = 'visited') AS applied_visited,
                   SUM(c.status = 'used') AS used
            FROM coupon c
            LEFT JOIN reservation r ON r.id = c.reservation_id
            """;
    private static final String STORE_COUPONS = COUPONS + """
            JOIN store s ON s.id = c.target_store_id
            WHERE c.issuer_kind = 'store' AND %s
            """.formatted(IN_SCOPE);
    private static final String ASSOCIATION_COUPONS = COUPONS + """
            JOIN association_coupon_event ev ON ev.id = c.association_event_id
            WHERE c.issuer_kind = 'association' AND ev.region_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final StoreService stores;

    ExtraMetricsService(JdbcTemplate jdbc, StoreService stores) {
        this.jdbc = jdbc;
        this.stores = stores;
    }

    /** 사장님: 내 가게 범위. 사장님 자기 가게 숫자는 숨기지 않고, 다른 가게가 섞이는 업종별 이동만 숨긴다 */
    @Transactional(readOnly = true)
    public Metrics forOwner(AccountPrincipal me) {
        long storeId = stores.requireApprovedOwnStore(me);
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        return metrics(regionId, storeId, false);
    }

    /** 상인회: 권역 전체. 사람 묶음은 모두 5명 미만 숨김(FR-ANL-05) */
    @Transactional(readOnly = true)
    public Metrics forAssociation(AccountPrincipal me) {
        me.requireRole(AccountRole.ASSOCIATION);
        return metrics(me.regionId(), null, true);
    }

    private Metrics metrics(long regionId, Long storeId, boolean association) {
        Object[] scope = {regionId, storeId, storeId};

        Funnel funnel = jdbc.queryForObject(FUNNEL, (rs, i) -> new Funnel(rs.getLong("clicks"), rs.getLong("exposures"),
                rs.getLong("reservations"), rs.getLong("visits")), concat(scope, scope, scope, scope));

        Conversion conversion = jdbc.queryForObject(CONVERSION, (rs, i) -> {
            long pairs = rs.getLong("pairs");
            long converted = rs.getLong("converted");
            return new Conversion(pairs, converted, rate(converted, pairs));
        }, scope);

        List<String[]> moves = jdbc.query(MOVES, (rs, i) -> new String[] {rs.getString("customer_id"),
                rs.getString("from_category"), rs.getString("to_category")}, concat(new Object[] {regionId}, scope, scope));
        MinimumGroup.Masked moveTotal = MinimumGroup.mask(moves.size(), moves.stream().map(m -> m[0]).distinct().count());

        return new Metrics(funnel, conversion, moveTotal, categoryMoves(moves), hourly(scope, association),
                coupons(STORE_COUPONS, scope, association),
                association ? coupons(ASSOCIATION_COUPONS, new Object[] {regionId}, true) : null);
    }

    /** 업종 → 업종 이동 비율 = 그 묶음 이동 수 ÷ 범위 안 전체 이동 수. 묶음 고유 손님이 1 ~ 4명이면 숨긴다 */
    private static List<CategoryMove> categoryMoves(List<String[]> moves) {
        List<CategoryMove> out = new ArrayList<>();
        moves.stream().map(m -> m[1] + ">" + m[2]).distinct().sorted().forEach(key -> {
            List<String[]> group = moves.stream().filter(m -> key.equals(m[1] + ">" + m[2])).toList();
            MinimumGroup.Masked masked = MinimumGroup.mask(group.size(), group.stream().map(m -> m[0]).distinct().count());
            String[] cats = key.split(">");
            out.add(new CategoryMove(cats[0], cats[1], masked.count(),
                    masked.belowMinimum() ? null : rate(group.size(), moves.size()), masked.belowMinimum()));
        });
        return out;
    }

    private List<HourlyReservations> hourly(Object[] scope, boolean mask) {
        return jdbc.query(HOURLY, (rs, i) -> {
            MinimumGroup.Masked m = mask ? MinimumGroup.mask(rs.getLong("cnt"), rs.getLong("people"))
                    : new MinimumGroup.Masked(rs.getLong("cnt"), false);
            return new HourlyReservations(rs.getInt("dow"), rs.getInt("hr"), m.count(), m.belowMinimum());
        }, scope);
    }

    private CouponMetrics coupons(String sql, Object[] params, boolean mask) {
        return jdbc.queryForObject(sql, (rs, i) -> {
            long downloads = rs.getLong("downloads");
            if (mask && MinimumGroup.mask(downloads, rs.getLong("people")).belowMinimum()) {
                return CouponMetrics.HIDDEN;
            }
            long applied = rs.getLong("applied");
            long expired = rs.getLong("expired");
            long appliedVisited = rs.getLong("applied_visited");
            return new CouponMetrics(downloads, applied, rate(applied, downloads), expired, rate(expired, downloads),
                    appliedVisited, rate(appliedVisited, applied), rs.getLong("used"), false);
        }, params);
    }

    /** 소수 넷째 자리까지(0.15 = 15%). 분모가 0이면 null(표시하지 않음) */
    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    private static Object[] concat(Object[]... parts) {
        List<Object> all = new ArrayList<>();
        for (Object[] p : parts) {
            all.addAll(Arrays.asList(p));
        }
        return all.toArray();
    }

    /** 단계별 건수: 가게 클릭 · 추천 노출(이 가게가 추천된 횟수) · 예약(모든 상태) · 방문 처리 */
    public record Funnel(long clicks, long exposures, long reservations, long visits) {
    }

    /** @param rate 분모가 0이면 null */
    public record Conversion(long exposedPairs, long convertedPairs, Double rate) {
    }

    /** @param moves · ratio 숨기면 null */
    public record CategoryMove(String fromCategory, String toCategory, Long moves, Double ratio, boolean belowMinimum) {
    }

    /** @param dayOfWeek 1(월) ~ 7(일), @param hour 0 ~ 23(운영 시간대) */
    public record HourlyReservations(int dayOfWeek, int hour, Long reservations, boolean belowMinimum) {
    }

    /** 비율의 분모: 적용률 · 만료율은 다운로드 수, 방문 완료율은 적용 수. 분모가 0이면 null. 숨기면 숫자 모두 null */
    public record CouponMetrics(Long downloads, Long applied, Double applyRate, Long expired, Double expireRate,
            Long appliedVisited, Double visitCompletionRate, Long used, boolean belowMinimum) {
        static final CouponMetrics HIDDEN = new CouponMetrics(null, null, null, null, null, null, null, null, true);
    }

    /** @param associationCoupons 상인회 쿠폰 지표. 사장님 응답에는 null */
    public record Metrics(Funnel funnel, Conversion recommendationConversion, MinimumGroup.Masked confirmedMoves,
            List<CategoryMove> categoryMoves, List<HourlyReservations> hourlyReservations, CouponMetrics storeCoupons,
            CouponMetrics associationCoupons) {
    }
}
