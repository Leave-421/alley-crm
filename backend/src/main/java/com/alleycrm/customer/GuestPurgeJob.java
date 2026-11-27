package com.alleycrm.customer;

import com.alleycrm.common.scheduler.ScheduledJob;
import com.alleycrm.reservation.DelayService;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비회원 정보 파기(C12, FR-CUS-07 · NFR-06). 자동 작업 `guest_purge`.
 * 방문 기록이 있으면 마지막 방문 후 N개월, 없으면 마지막 예약 취소 후 N일이 지난 비회원의
 * 이름 · 전화번호 · 사장님 메모 · 지연 입력 도착 시간을 지운다. 예약 · 방문 기록과 성별 · 연령대(비식별 집계)는 남긴다.
 * 신청 · 확정 예약이 남아 있는 손님은 건너뛴다. 이미 파기한 손님은 다시 보지 않으므로 여러 번 돌아도 결과가 같다.
 */
@Component
class GuestPurgeJob implements ScheduledJob {

    /** 권역 안 예약이 하나라도 있는 비회원 중 파기 시점이 지난 손님. 시각은 칸이 비면 예약 시각으로 본다. */
    // shortcut: 여러 권역에 예약한 비회원은 보관 기간이 먼저 끝나는 권역 기준으로 파기된다, 권역 선택(v1 제외)이 생기면 다시 정한다.
    private static final String DUE = """
            SELECT c.id
              FROM customer c
              JOIN reservation r ON r.customer_id = c.id
             WHERE c.type = 'guest' AND c.purged_at IS NULL
               AND EXISTS (SELECT 1 FROM reservation x JOIN store s ON s.id = x.store_id
                            WHERE x.customer_id = c.id AND s.region_id = :region)
             GROUP BY c.id
            HAVING SUM(r.status IN ('requested', 'confirmed')) = 0
               AND CASE WHEN SUM(r.status = 'visited') > 0
                        THEN MAX(CASE WHEN r.status = 'visited' THEN COALESCE(r.visited_at, r.start_at) END) < :visitCutoff
                        ELSE MAX(COALESCE(r.canceled_at, r.start_at)) < :cancelCutoff
                   END
            """;

    private final NamedParameterJdbcTemplate named;
    private final SettingService settings;
    private final DelayService delays;
    private final Clock clock;

    GuestPurgeJob(NamedParameterJdbcTemplate named, SettingService settings, DelayService delays, Clock clock) {
        this.named = named;
        this.settings = settings;
        this.delays = delays;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "guest_purge";
    }

    @Override
    @Transactional
    public int run() {
        Instant now = Instant.now(clock);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        int n = 0;
        for (Long region : named.getJdbcTemplate().queryForList("SELECT id FROM region", Long.class)) {
            List<Long> ids = named.queryForList(DUE, Map.of(
                    "region", region,
                    "visitCutoff", nowUtc.minusMonths(settings.getInt(region, SettingKey.GUEST_RETENTION_MONTHS)),
                    "cancelCutoff", nowUtc.minusDays(settings.getInt(region, SettingKey.GUEST_NO_VISIT_RETENTION_DAYS))), Long.class);
            if (ids.isEmpty()) {
                continue;
            }
            Map<String, Object> p = Map.of("ids", ids, "now", nowUtc);
            n += named.update("UPDATE customer SET name = NULL, phone = NULL, purged_at = :now WHERE id IN (:ids) AND purged_at IS NULL", p);
            named.update("UPDATE customer_card SET memo = NULL WHERE customer_id IN (:ids)", p);
            delays.forgetEta(ids);
        }
        return n;
    }
}
