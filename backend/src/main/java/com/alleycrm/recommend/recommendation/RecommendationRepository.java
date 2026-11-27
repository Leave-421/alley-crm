package com.alleycrm.recommend.recommendation;

import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 지도 추천(E7)에 쓰는 읽기. 가게 · 쿠폰 행사 · 손님 · 예약 · 공공데이터 표를 추천 조회로 읽기만 한다(네이티브 SQL). 시각은 UTC 값이다.
 */
@Repository
class RecommendationRepository {

    /** 카드에 그릴 가게 정보(CONTRACTS_API 3.1 RecommendationCard.store). */
    record StoreInfo(long id, String name, String category, String alley, Double mapX, Double mapY) {
    }

    /** 화면 출처 표시(NFR-10 #2). */
    record PublicData(String source, String period) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    RecommendationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** 손님에게 보이는 가게(승인 · 탈퇴 신청 중)만. 없거나 승인 전 · 거절 · 탈퇴면 빈 값. */
    Optional<StoreInfo> findVisibleStore(long storeId) {
        return jdbc.query("SELECT id, name, category, alley, map_x, map_y FROM store WHERE id = ? AND join_status IN ('approved', 'withdraw_requested')",
                (rs, n) -> storeInfo(rs), storeId).stream().findFirst();
    }

    Map<Long, StoreInfo> findStores(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return named.query("SELECT id, name, category, alley, map_x, map_y FROM store WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), (rs, n) -> storeInfo(rs)).stream()
                .collect(Collectors.toMap(StoreInfo::id, Function.identity()));
    }

    /** '예약 혜택' 표시값: 중단되지 않은 쿠폰 행사가 있고 총수량이 남은 가게(FR-CPN-02 · 11). 순위에는 쓰지 않는다. */
    Set<Long> storesWithCouponBenefit(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(named.queryForList("""
                SELECT store_id FROM coupon_policy
                WHERE stopped_at IS NULL AND issued_count < total_quantity AND store_id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), Long.class));
    }

    /** 회원정보의 성별 · 연령대(없으면 null). */
    Profile memberProfile(long customerId) {
        return jdbc.query("SELECT gender, age_group FROM customer WHERE id = ?",
                        (rs, n) -> new Profile(rs.getString("gender"), rs.getString("age_group")), customerId)
                .stream().findFirst().orElse(Profile.UNKNOWN);
    }

    /** 이 손님의 그 가게 신청 · 확정 예약 중 지금 이후 가장 가까운 시작 시각(3.1 R-6). */
    Optional<LocalDateTime> nearestUpcomingReservation(long customerId, long storeId, LocalDateTime nowUtc) {
        return jdbc.queryForList("""
                SELECT MIN(start_at) FROM reservation
                WHERE customer_id = ? AND store_id = ? AND status IN ('requested', 'confirmed') AND start_at >= ?
                """, LocalDateTime.class, customerId, storeId, nowUtc).stream().filter(t -> t != null).findFirst();
    }

    /** 기준 예약: 손님 · 가게 · 시작 시각(UTC). */
    record BaseReservation(long id, long customerId, long storeId, LocalDateTime startAt) {
    }

    Optional<BaseReservation> findReservation(long reservationId) {
        return jdbc.query("SELECT id, customer_id, store_id, start_at FROM reservation WHERE id = ?",
                (rs, n) -> new BaseReservation(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                        rs.getObject("start_at", LocalDateTime.class)), reservationId).stream().findFirst();
    }

    long regionOf(long storeId) {
        return jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
    }

    /** 공공데이터 출처 · 기준 기간. 출처가 여럿이면 ' · '로 잇는다. 적재 전이면 빈 값. */
    Optional<PublicData> publicData() {
        List<String> sources = jdbc.queryForList("SELECT DISTINCT source FROM public_stat ORDER BY source", String.class);
        List<String> periods = jdbc.queryForList("SELECT DISTINCT period FROM public_stat ORDER BY period", String.class);
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PublicData(String.join(" · ", sources), String.join(" · ", periods)));
    }

    private static StoreInfo storeInfo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoreInfo(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("alley"),
                toDouble(rs.getBigDecimal("map_x")), toDouble(rs.getBigDecimal("map_y")));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
