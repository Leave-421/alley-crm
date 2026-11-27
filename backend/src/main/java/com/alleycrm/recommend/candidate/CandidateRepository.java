package com.alleycrm.recommend.candidate;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 추천 후보 조회(05-policy 「다음 방문 추천」 1단계). 가게 · 예약 · 손님 표를 읽기만 한다(추천 조회, 네이티브 SQL).
 * 시각은 UTC 값이다.
 */
@Repository
class CandidateRepository {

    private final JdbcTemplate jdbc;

    CandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 기준 가게와 같은 권역의 참여(승인된) 가게 중 지금 예약을 받는 가게. 기준 가게 자신은 뺀다.
     * 기준 가게가 없으면 빈 목록.
     */
    List<Long> findBookableStoresInRegionOf(long baseStoreId) {
        return jdbc.queryForList("""
                SELECT s.id
                FROM store s
                JOIN store base ON base.id = ? AND s.region_id = base.region_id
                WHERE s.id <> base.id
                  AND s.join_status IN ('approved', 'withdraw_requested')
                  AND s.business_status = 'open'
                  AND s.reservation_enabled = TRUE
                ORDER BY s.id
                """, Long.class, baseStoreId);
    }

    /**
     * 손님이 [from, to)에 시작하는 예약을 가진 가게(취소된 예약은 뺀다). 회원은 손님 ID, 비회원은 파기되지 않은
     * 같은 전화번호의 비회원 손님으로 찾는다. 둘 다 null이면 빈 목록.
     */
    List<Long> findStoresReservedBetween(Long memberCustomerId, String guestPhone, LocalDateTime from,
            LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT DISTINCT r.store_id
                FROM reservation r
                JOIN customer c ON c.id = r.customer_id
                WHERE r.status <> 'canceled'
                  AND r.start_at >= ? AND r.start_at < ?
                  AND (c.id = ? OR (c.type = 'guest' AND c.purged_at IS NULL AND c.phone = ?))
                """, Long.class, from, to, memberCustomerId, guestPhone);
    }
}
