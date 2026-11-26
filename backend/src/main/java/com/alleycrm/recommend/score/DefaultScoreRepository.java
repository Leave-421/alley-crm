package com.alleycrm.recommend.score;

import com.alleycrm.recommend.domain.PublicStatKind;
import com.alleycrm.recommend.domain.TimeBand;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 기본값 점수 계산에 쓰는 읽기: 공공데이터(public_stat) · 가게 업종 · 지도 좌표 · 권역. 추천 조회라 네이티브 SQL로 읽기만 한다. */
@Repository
class DefaultScoreRepository {

    /** 가게 한 곳의 계산 재료. */
    record StoreRow(long id, long regionId, String category, Double x, Double y) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    DefaultScoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    List<StoreRow> findStores(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT id, region_id, category, map_x, map_y FROM store WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                (rs, n) -> new StoreRow(rs.getLong("id"), rs.getLong("region_id"), rs.getString("category"),
                        toDouble(rs.getBigDecimal("map_x")), toDouble(rs.getBigDecimal("map_y"))));
    }

    PublicStats loadPublicStats() {
        Map<String, Double> attractiveness = new HashMap<>();
        Map<String, Map<String, Double>> clq = new HashMap<>();
        Map<String, Map<DayOfWeek, Double>> day = new HashMap<>();
        Map<String, Map<TimeBand, Double>> band = new HashMap<>();
        Map<String, Map<String, Double>> gender = new HashMap<>();
        Map<String, Map<String, Double>> age = new HashMap<>();
        jdbc.query("SELECT kind, category_from, category_to, slot, gender, age_group, value FROM public_stat", rs -> {
            PublicStatKind kind = PublicStatKind.fromCode(rs.getString("kind")).orElse(null);
            if (kind == null) {
                return; // 모르는 종류는 계산에 쓰지 않는다(적재 때 이미 검사함)
            }
            String to = rs.getString("category_to");
            double value = rs.getBigDecimal("value").doubleValue();
            switch (kind) {
                case ATTRACTIVENESS -> attractiveness.put(to, value);
                case CLQ -> clq.computeIfAbsent(rs.getString("category_from"), k -> new HashMap<>()).put(to, value);
                case DAY_SHARE -> day.computeIfAbsent(to, k -> new HashMap<>()).put(dayOf(rs.getString("slot")), value);
                case TIME_BAND_SHARE -> TimeBand.fromCode(rs.getString("slot"))
                        .ifPresent(b -> band.computeIfAbsent(to, k -> new HashMap<>()).put(b, value));
                case GENDER_SHARE -> gender.computeIfAbsent(to, k -> new HashMap<>()).put(rs.getString("gender"), value);
                case AGE_SHARE -> age.computeIfAbsent(to, k -> new HashMap<>()).put(rs.getString("age_group"), value);
            }
        });
        return new PublicStats(attractiveness, clq, day, band, gender, age);
    }

    /** {@code mon} ~ {@code sun} → 요일. */
    static DayOfWeek dayOf(String code) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().substring(0, 3).toLowerCase(Locale.ROOT).equals(code)) {
                return d;
            }
        }
        throw new IllegalStateException("모르는 요일 값: " + code);
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
