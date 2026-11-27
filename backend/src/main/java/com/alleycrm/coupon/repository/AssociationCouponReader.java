package com.alleycrm.coupon.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 상인회 쿠폰 행사(E18)가 읽기만 하는 값: 쓸 수 있는 가게 검사(가게 표)와 쓴 예산(쿠폰 집계). 네이티브 SQL로 읽기만 한다.
 */
@Repository
public class AssociationCouponReader {

    private final NamedParameterJdbcTemplate jdbc;

    public AssociationCouponReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code ids} 중 그 권역의 참여 가게(승인 · 탈퇴 신청 중). */
    public Set<Long> approvedStoresInRegion(long regionId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM store WHERE region_id = :regionId AND join_status IN ('approved', 'withdraw_requested') AND id IN (:ids)
                """, new MapSqlParameterSource("regionId", regionId).addValue("ids", ids), Long.class));
    }

    /** 그 가게가 승인된(참여) 가게면 권역 ID. 상인회 쿠폰의 쓸 수 있는 가게 판단(3.10). */
    public Optional<Long> approvedStoreRegion(long storeId) {
        return jdbc.queryForList("SELECT region_id FROM store WHERE id = :id AND join_status = 'approved'",
                new MapSqlParameterSource("id", storeId), Long.class).stream().findFirst();
    }

    /** 가게 ID → 이름(ID 순). 쓸 수 있는 가게를 회원 카드에 보여줄 때(3.9). */
    public Map<Long, String> storeNames(Collection<Long> ids) {
        Map<Long, String> names = new TreeMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT id, name FROM store WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                });
        return names;
    }

    /**
     * 권역 행사별 쓴 예산: 예약에 적용 · 사용된 쿠폰의 실제 할인액, 아직 방문 전이면 한 장 최대 할인액
     * (CONTRACTS_API 3.8 · 2절 {@code releaseDue}와 같은 계산). 쓴 쿠폰이 없는 행사는 맵에 없다.
     */
    public Map<Long, Long> usedBudgets(long regionId) {
        Map<Long, Long> used = new HashMap<>();
        jdbc.query("""
                SELECT c.association_event_id AS event_id,
                       SUM(COALESCE(c.discount_applied,
                           CASE WHEN e.benefit_type = 'fixed_discount' THEN e.discount_amount ELSE e.max_discount_amount END)) AS used
                FROM coupon c
                JOIN association_coupon_event e ON e.id = c.association_event_id
                WHERE e.region_id = :regionId AND c.status IN ('applied', 'used')
                GROUP BY c.association_event_id
                """, new MapSqlParameterSource("regionId", regionId),
                rs -> {
                    used.put(rs.getLong("event_id"), rs.getLong("used"));
                });
        return used;
    }
}
