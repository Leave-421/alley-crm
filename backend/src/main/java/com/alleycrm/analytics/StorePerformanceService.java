package com.alleycrm.analytics;

import static com.alleycrm.analytics.SuccessMetricsService.rate;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.analytics.SuccessMetricsService.Acceptance;
import com.alleycrm.analytics.SuccessMetricsService.Rate;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상인회 가게 성과 비교(A-02, FR-ANL-04 · 07, C19). 전체 기간, 참여 가게마다 예약 · 방문 · 사유별 취소율, 소개 실적,
 * 재방문율(가게 단위), 수락률. 사람 묶음 칸은 고유 손님이 1 ~ 4명이면 숨긴다(FR-ANL-05).
 */
@Service
public class StorePerformanceService {

    /** 취소율 분모는 결과가 정해진 예약(방문 + 취소). 신청 · 확정 중인 예약은 예약 수에만 든다 */
    private static final String RESERVATIONS = """
            SELECT r.store_id, COUNT(*) AS total, COUNT(DISTINCT r.customer_id) AS people,
                   SUM(r.status = 'visited') AS visited, SUM(r.status = 'canceled') AS canceled,
                   SUM(r.status = 'canceled' AND r.cancel_reason = 'customer_canceled') AS customer_canceled,
                   SUM(r.status = 'canceled' AND r.cancel_reason = 'store_rejected') AS store_rejected,
                   SUM(r.status = 'canceled' AND r.cancel_reason = 'auto_canceled') AS auto_canceled
            FROM reservation r JOIN store s ON s.id = r.store_id
            WHERE s.region_id = ?
            GROUP BY r.store_id
            """;

    /** 확정된 소개(referral_fee 한 행 = 예약 한 건). 낸 수수료는 받은 가게 기준, 금액 신고 정정 차액 포함 */
    private static final String REFERRALS = """
            SELECT f.%s AS store_id, COUNT(*) AS cnt, COUNT(DISTINCT r.customer_id) AS people,
                   COALESCE(SUM(f.fee_amount), 0)
                   + COALESCE(SUM((SELECT COALESCE(SUM(x.fee_difference), 0) FROM visit_amount_correction x
                                   WHERE x.reservation_id = f.reservation_id)), 0) AS fee
            FROM referral_fee f
            JOIN reservation r ON r.id = f.reservation_id
            JOIN store s ON s.id = f.%1$s
            WHERE s.region_id = ?
            GROUP BY f.%1$s
            """;

    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final SuccessMetricsService metrics;

    StorePerformanceService(JdbcTemplate jdbc, SettingService settings, SuccessMetricsService metrics) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Performance performance(AccountPrincipal me) {
        me.requireRole(AccountRole.ASSOCIATION);
        long regionId = me.regionId();
        int revisitDays = settings.getInt(regionId, SettingKey.REVISIT_DAYS);

        Map<String, Reservations> reservations = new HashMap<>();
        jdbc.query(RESERVATIONS, rs -> {
            long decided = rs.getLong("visited") + rs.getLong("canceled");
            reservations.put(rs.getString("store_id"), MinimumGroup.mask(rs.getLong("total"), rs.getLong("people")).belowMinimum()
                    ? Reservations.HIDDEN
                    : new Reservations(rs.getLong("total"), rs.getLong("visited"), rs.getLong("canceled"),
                            rate(rs.getLong("canceled"), decided),
                            new CancelRates(rate(rs.getLong("customer_canceled"), decided),
                                    rate(rs.getLong("store_rejected"), decided),
                                    rate(rs.getLong("auto_canceled"), decided)),
                            false));
        }, regionId);
        Map<String, Sent> sent = new HashMap<>();
        jdbc.query(REFERRALS.formatted("sender_store_id"), rs -> {
            MinimumGroup.Masked m = MinimumGroup.mask(rs.getLong("cnt"), rs.getLong("people"));
            sent.put(rs.getString("store_id"), new Sent(m.count(), m.belowMinimum()));
        }, regionId);
        Map<String, Received> received = new HashMap<>();
        jdbc.query(REFERRALS.formatted("receiving_store_id"), rs -> {
            MinimumGroup.Masked m = MinimumGroup.mask(rs.getLong("cnt"), rs.getLong("people"));
            received.put(rs.getString("store_id"),
                    new Received(m.count(), m.belowMinimum() ? null : rs.getLong("fee"), m.belowMinimum()));
        }, regionId);
        Map<String, Rate> revisit = metrics.revisitByStore(revisitDays, regionId);
        Map<String, Acceptance> acceptance = metrics.acceptanceByStore(regionId);

        List<StorePerformance> stores = jdbc.query("""
                SELECT id, name FROM store WHERE region_id = ? AND join_status IN ('approved', 'withdraw_requested')
                ORDER BY name, id
                """, (rs, i) -> {
            String id = String.valueOf(rs.getLong("id"));
            return new StorePerformance(id, rs.getString("name"),
                    reservations.getOrDefault(id, Reservations.EMPTY),
                    new Referral(sent.getOrDefault(id, Sent.EMPTY), received.getOrDefault(id, Received.EMPTY)),
                    revisit.getOrDefault(id, Rate.EMPTY),
                    acceptance.getOrDefault(id, Acceptance.EMPTY));
        }, regionId);
        return new Performance(revisitDays, stores);
    }

    /** 사유별 취소율. 분모는 전체 취소율과 같다 */
    public record CancelRates(Double customerCanceled, Double storeRejected, Double autoCanceled) {
    }

    /**
     * @param total 모든 상태의 예약 수
     * @param cancelRate 취소 ÷ (방문 + 취소). 분모가 0이면 null
     */
    public record Reservations(Long total, Long visited, Long canceled, Double cancelRate, CancelRates cancelRateByReason,
            boolean belowMinimum) {
        static final Reservations EMPTY = new Reservations(0L, 0L, 0L, null, new CancelRates(null, null, null), false);
        static final Reservations HIDDEN = new Reservations(null, null, null, null, null, true);
    }

    /** @param count 이 가게 예약에서 만든 제안으로 확정된 소개 수 */
    public record Sent(Long count, boolean belowMinimum) {
        static final Sent EMPTY = new Sent(0L, false);
    }

    /** @param count 이 가게가 받은 확정 소개 수, @param feePaid 낸 수수료(정정 차액 포함) */
    public record Received(Long count, Long feePaid, boolean belowMinimum) {
        static final Received EMPTY = new Received(0L, 0L, false);
    }

    public record Referral(Sent sent, Received received) {
    }

    public record StorePerformance(String storeId, String storeName, Reservations reservations, Referral referral,
            Rate revisit, Acceptance acceptance) {
    }

    public record Performance(int revisitDays, List<StorePerformance> stores) {
    }
}
