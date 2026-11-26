package com.alleycrm.recommend.todaycourse;

import com.alleycrm.recommend.recommendation.RecommendationDtos.CardStore;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Visit;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 오늘의 코스 계산 입력. 추천 조회라 다른 도메인 표(예약 · 가게 · 쿠폰 행사)를 네이티브 SQL로 읽기만 한다.
 * 손님 ID는 고유 손님을 세는 데만 쓰고 밖으로 내보내지 않는다(FR-CUS-05).
 */
@Repository
class TodayCourseRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    TodayCourseRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    boolean regionExists(long regionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM region WHERE id = ?)",
                Boolean.class, regionId));
    }

    /** 권역 가게의 방문 처리된 예약. 실제 운영 · 시연 데이터를 모두 쓴다(E6과 같음). */
    List<Visit> findVisitsInRegion(long regionId) {
        return jdbc.query("""
                SELECT r.id, r.customer_id, r.store_id, r.start_at
                FROM reservation r
                JOIN store s ON s.id = r.store_id
                WHERE r.status = 'visited' AND s.region_id = ?
                """, (rs, n) -> new Visit(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                rs.getObject("start_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), regionId);
    }

    /** 지금 예약을 받는 가게(3.1 후보와 같은 기준: 승인 · 영업 중 · 예약 받기 켜짐). */
    Set<Long> findBookableStores(long regionId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM store
                WHERE region_id = ? AND join_status = 'approved' AND business_status = 'open'
                  AND reservation_enabled = TRUE
                """, Long.class, regionId));
    }

    Map<Long, CardStore> findStores(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return named.query("SELECT id, name, category, alley, map_x, map_y FROM store WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), (rs, n) -> new CardStore(String.valueOf(rs.getLong("id")),
                        rs.getString("name"), rs.getString("category"), rs.getString("alley"),
                        toDouble(rs.getBigDecimal("map_x")), toDouble(rs.getBigDecimal("map_y"))))
                .stream().collect(Collectors.toMap(s -> Long.parseLong(s.id()), Function.identity()));
    }

    /** 쿠폰 행사 참여 가게: 중단되지 않은 행사가 있고 총수량이 남음(3.1 {@code couponBenefit}과 같은 기준). */
    Set<Long> findStoresWithCouponBenefit(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(named.queryForList("""
                SELECT store_id FROM coupon_policy
                WHERE stopped_at IS NULL AND issued_count < total_quantity AND store_id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), Long.class));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
