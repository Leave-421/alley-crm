package com.alleycrm.recommend.score;

import com.alleycrm.recommend.score.RecordScoreCalculator.Visit;
import com.alleycrm.reservation.request.SlotRules;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 실제 기록 점수(E6)에 쓰는 방문 기록: 권역의 방문 처리된 예약과 손님 성별 · 연령대. 추천 조회라 네이티브 SQL로 읽기만 하고,
 * 개인 단위 값은 점수 계산에만 쓰고 밖으로 내보내지 않는다(FR-CUS-05).
 */
@Repository
class VisitRecordRepository {

    private final JdbcTemplate jdbc;

    VisitRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 같은 날 · 순서는 예약 시작 시각(운영 시간대 날짜)으로 본다. 실제 운영 · 시연 데이터를 모두 쓴다. */
    List<Visit> findVisitsInRegion(long regionId) {
        return jdbc.query("""
                SELECT r.customer_id, r.store_id, r.start_at, c.gender, c.age_group
                FROM reservation r
                JOIN store s ON s.id = r.store_id
                JOIN customer c ON c.id = r.customer_id
                WHERE r.status = 'visited' AND s.region_id = ?
                """, (rs, n) -> {
                    LocalDateTime startUtc = rs.getObject("start_at", LocalDateTime.class);
                    var start = startUtc.toInstant(ZoneOffset.UTC);
                    return new Visit(rs.getLong("customer_id"), rs.getLong("store_id"),
                            LocalDateTime.ofInstant(start, SlotRules.SERVICE_ZONE).toLocalDate(), start.toEpochMilli(),
                            rs.getString("gender"), rs.getString("age_group"));
                }, regionId);
    }
}
