package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상인회 방문 흐름(A-03, FR-ANL-03 · 05, C20). 참여 가게 사이 방향 있는 연결마다 확인된 연계 방문 수와 추천 노출 수를
 * 나눠 주고, 고유 인원이 5명 미만이면 숨긴다. 집계라 다른 도메인 표를 네이티브 SQL로 읽기만 한다. 개인 단위 값은 내보내지 않는다.
 */
@Service
public class VisitFlowService {

    static final int TOP = 3;

    /**
     * 확인된 연계 방문: 같은 손님이 같은 날(예약 시작 시각, 운영 시간대 Asia/Seoul) 연달아 방문 처리한 두 가게.
     * 날짜 · 순서 기준은 추천 실제 기록 점수(E6 VisitRecordRepository)와 같다. 같은 가게를 연달아 간 것은 이동이 아니다.
     */
    private static final String MOVES = """
            SELECT from_store, to_store, COUNT(*) AS cnt, COUNT(DISTINCT customer_id) AS people
            FROM (
                SELECT r.customer_id, r.store_id AS to_store,
                       LAG(r.store_id) OVER (PARTITION BY r.customer_id, DATE(DATE_ADD(r.start_at, INTERVAL 9 HOUR))
                                             ORDER BY r.start_at, r.id) AS from_store
                FROM reservation r JOIN store s ON s.id = r.store_id
                WHERE r.status = 'visited' AND s.region_id = ?
            ) t
            WHERE from_store IS NOT NULL AND from_store <> to_store
            GROUP BY from_store, to_store
            """;

    /** 추천 노출: A를 기준 가게로 B를 추천 카드에 보여준 횟수. 사람 수는 고유 방문자(브라우저) 수 */
    private static final String EXPOSURES = """
            SELECT e.base_store_id AS from_store, e.recommended_store_id AS to_store,
                   COUNT(*) AS cnt, COUNT(DISTINCT e.visitor_id) AS people
            FROM recommendation_exposure e JOIN store s ON s.id = e.base_store_id
            WHERE s.region_id = ?
            GROUP BY e.base_store_id, e.recommended_store_id
            """;

    private final JdbcTemplate jdbc;

    VisitFlowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Flows flows(AccountPrincipal me) {
        me.requireRole(AccountRole.ASSOCIATION);
        long regionId = me.regionId();
        // 지도에 그리는 참여 가게(승인 · 탈퇴 신청 중) 사이의 연결만. 탈퇴 가게 기록은 지우지 않지만 선은 그리지 않는다
        List<Long> participating = jdbc.queryForList(
                "SELECT id FROM store WHERE region_id = ? AND join_status IN ('approved', 'withdraw_requested')",
                Long.class, regionId);
        Map<List<Long>, long[]> moves = counts(MOVES, regionId);
        Map<List<Long>, long[]> exposures = counts(EXPOSURES, regionId);
        Comparator<List<Long>> byIds = Comparator.<List<Long>>comparingLong(p -> p.get(0)).thenComparingLong(p -> p.get(1));
        List<List<Long>> pairs = union(moves, exposures).stream()
                .filter(p -> participating.contains(p.get(0)) && participating.contains(p.get(1)))
                .sorted(byIds).toList();
        List<Link> all = pairs.stream().map(p -> link(p, moves, exposures)).toList();
        // 인기 연결: 숨기지 않은 연결 중 확인된 연계 방문이 많은 순(같으면 가게 ID 순)
        List<Link> top = pairs.stream()
                .filter(p -> moves.containsKey(p) && moves.get(p)[1] >= MinimumGroup.MIN_PEOPLE)
                .sorted(Comparator.<List<Long>>comparingLong(p -> moves.get(p)[0]).reversed().thenComparing(byIds))
                .limit(TOP).map(p -> link(p, moves, exposures)).toList();
        return new Flows(all, top);
    }

    private static Link link(List<Long> pair, Map<List<Long>, long[]> moves, Map<List<Long>, long[]> exposures) {
        long[] m = moves.getOrDefault(pair, new long[] {0, 0});
        long[] e = exposures.getOrDefault(pair, new long[] {0, 0});
        return new Link(String.valueOf(pair.get(0)), String.valueOf(pair.get(1)),
                MinimumGroup.mask(m[0], m[1]), MinimumGroup.mask(e[0], e[1]));
    }

    private Map<List<Long>, long[]> counts(String sql, long regionId) {
        Map<List<Long>, long[]> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(List.of(rs.getLong("from_store"), rs.getLong("to_store")), new long[] {rs.getLong("cnt"), rs.getLong("people")});
        }, regionId);
        return out;
    }

    private static List<List<Long>> union(Map<List<Long>, long[]> a, Map<List<Long>, long[]> b) {
        Set<List<Long>> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return List.copyOf(keys);
    }

    /**
     * 가게 A → B 연결 하나.
     *
     * @param moves 확인된 연계 방문 수(선 굵기). 고유 손님 5명 미만이면 숨김
     * @param exposures A를 기준으로 B를 보여준 추천 노출 수. 고유 방문자 5명 미만이면 숨김
     */
    public record Link(String fromStoreId, String toStoreId, MinimumGroup.Masked moves, MinimumGroup.Masked exposures) {
    }

    public record Flows(List<Link> links, List<Link> top) {
    }
}
