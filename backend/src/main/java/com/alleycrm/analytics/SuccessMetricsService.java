package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.ServiceTime;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상인회 성공 지표(A-01, FR-ANL-06, C18): 코스 제안 수락률 · 재방문율(권역 · 가게) · 그 달 소개 수수료 총액.
 * 집계라 다른 도메인 표를 네이티브 SQL로 읽기만 한다. 재방문율은 사람 묶음이라 5명 미만이면 숨긴다(FR-ANL-05).
 */
@Service
public class SuccessMetricsService {

    /** 분모: 권역 가게 예약에 표시된 정식 코스 제안(조합당 1행). 분자: 그중 소개가 확정된(수수료가 생긴) 제안, 제안당 1 */
    private static final String ACCEPTANCE = """
            SELECT COUNT(*) AS shown,
                   COUNT(DISTINCT CASE WHEN EXISTS (SELECT 1 FROM reservation cr JOIN referral_fee f ON f.reservation_id = cr.id
                                                     WHERE cr.course_proposal_id = p.id) THEN p.id END) AS accepted
            FROM course_proposal p
            JOIN reservation b ON b.id = p.base_reservation_id
            JOIN store s ON s.id = b.store_id
            WHERE s.region_id = ?
            """;

    /**
     * 손님 · 묶음(권역이면 0, 가게면 가게 ID)마다 방문한 날(예약 시작 시각, 운영 시간대)을 하루 한 번으로 줄이고,
     * 바로 앞 방문일과의 간격이 1 ~ N일인 날이 있으면 재방문한 손님이다(같은 날 두 번은 재방문이 아님).
     */
    private static final String REVISIT = """
            SELECT grp, COUNT(*) AS customers, SUM(revisited) AS revisited
            FROM (
                SELECT grp, customer_id, MAX(gap BETWEEN 1 AND ?) AS revisited
                FROM (
                    SELECT grp, customer_id,
                           DATEDIFF(day, LAG(day) OVER (PARTITION BY grp, customer_id ORDER BY day)) AS gap
                    FROM (
                        SELECT DISTINCT %s AS grp, r.customer_id, DATE(DATE_ADD(r.start_at, INTERVAL 9 HOUR)) AS day
                        FROM reservation r JOIN store s ON s.id = r.store_id
                        WHERE r.status = 'visited' AND s.region_id = ?
                    ) d
                ) g
                GROUP BY grp, customer_id
            ) c
            GROUP BY grp
            """;

    /** 그 달 수수료 합계 + 상인회가 '맞음'으로 기록한 금액 신고의 수수료 차액(월 정산 화면과 같은 기준) */
    private static final String FEE_TOTAL = """
            SELECT COALESCE(SUM(f.fee_amount), 0)
                   + COALESCE(SUM((SELECT COALESCE(SUM(x.fee_difference), 0) FROM visit_amount_correction x
                                   WHERE x.reservation_id = f.reservation_id)), 0)
            FROM referral_fee f JOIN store s ON s.id = f.receiving_store_id
            WHERE s.region_id = ? AND f.settlement_month = ?
            """;

    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final Clock clock;

    SuccessMetricsService(JdbcTemplate jdbc, SettingService settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** @param month YYYY-MM, 없으면 이번 달(운영 시간대). 수수료 총액에만 쓴다 */
    @Transactional(readOnly = true)
    public Metrics metrics(AccountPrincipal me, String month) {
        me.requireRole(AccountRole.ASSOCIATION);
        long regionId = me.regionId();
        YearMonth ym = month(month);
        int revisitDays = settings.getInt(regionId, SettingKey.REVISIT_DAYS);

        Acceptance acceptance = jdbc.queryForObject(ACCEPTANCE, (rs, i) -> {
            long shown = rs.getLong("shown");
            long accepted = rs.getLong("accepted");
            return new Acceptance(shown, accepted, shown == 0 ? null : rate(accepted, shown));
        }, regionId);

        Map<String, Rate> region = revisit("0", revisitDays, regionId);
        Map<String, Rate> byStore = revisit("r.store_id", revisitDays, regionId);
        List<StoreRevisit> stores = jdbc.query("""
                SELECT id, name FROM store WHERE region_id = ? AND join_status IN ('approved', 'withdraw_requested')
                ORDER BY name, id
                """, (rs, i) -> {
            String id = String.valueOf(rs.getLong("id"));
            return new StoreRevisit(id, rs.getString("name"), byStore.getOrDefault(id, Rate.EMPTY));
        }, regionId);

        Long feeTotal = jdbc.queryForObject(FEE_TOTAL, Long.class, regionId, ym.toString());
        return new Metrics(acceptance, new RevisitSummary(revisitDays, region.getOrDefault("0", Rate.EMPTY), stores),
                new FeeTotal(ym.toString(), feeTotal));
    }

    /** 묶음 → 재방문율. 방문한 손님이 1 ~ 4명이면 숨긴다 */
    private Map<String, Rate> revisit(String group, int days, long regionId) {
        Map<String, Rate> out = new HashMap<>();
        jdbc.query(REVISIT.formatted(group), rs -> {
            long customers = rs.getLong("customers");
            long revisited = rs.getLong("revisited");
            out.put(rs.getString("grp"), MinimumGroup.mask(customers, customers).belowMinimum()
                    ? new Rate(null, null, null, true)
                    : new Rate(customers, revisited, rate(revisited, customers), false));
        }, days, regionId);
        return out;
    }

    private YearMonth month(String month) {
        if (month == null) {
            return YearMonth.from(Instant.now(clock).atZone(ServiceTime.ZONE));
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }

    /** 소수 넷째 자리까지(0.15 = 15%) */
    private static double rate(long numerator, long denominator) {
        return Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    /** @param rate 분모가 0이면 null(표시하지 않음, FR-ANL-06 #3) */
    public record Acceptance(long shownProposals, long acceptedProposals, Double rate) {
    }

    /** 방문한 손님 수 · 재방문한 손님 수 · 비율. 숨기면(belowMinimum) 숫자는 모두 null, 방문이 없으면 0 · 0 · null */
    public record Rate(Long customers, Long revisitedCustomers, Double rate, boolean belowMinimum) {
        static final Rate EMPTY = new Rate(0L, 0L, null, false);
    }

    public record StoreRevisit(String storeId, String storeName, Rate revisit) {
    }

    public record RevisitSummary(int revisitDays, Rate region, List<StoreRevisit> stores) {
    }

    public record FeeTotal(String month, long amount) {
    }

    public record Metrics(Acceptance acceptance, RevisitSummary revisit, FeeTotal referralFee) {
    }
}
