package com.alleycrm.customer;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.reservation.owner.NameMask;
import com.alleycrm.store.StoreService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 카드 · 메모(C9, FR-CUS-03)와 손님 등급(C10, FR-CUS-04). 사장님은 자기 가게 손님만 본다.
 * 등급은 저장하지 않고 그 가게 예약에서 바로 센다(방문 처리만, 그 가게 기준). 예약은 읽기만 하고,
 * customer_card에는 메모만 쓴다. 다른 가게 방문 · 보낸 가게는 응답에 없다(FR-CUS-05).
 */
@Service
public class CustomerCardService {

    /** `customer.grade` (CONTRACTS.md 「3」) */
    public static final String GRADE_NEW = "new";
    public static final String GRADE_REGULAR = "regular";
    public static final String GRADE_LOYAL = "loyal";
    public static final String GRADE_AT_RISK = "at_risk";
    static final String GRADES = "new|regular|loyal|at_risk";
    /** 단골이 이 기간을 넘게 오지 않으면 이탈 위험(05-policy 「손님 등급」) */
    static final Duration AT_RISK_AFTER = Duration.ofDays(60);
    static final int MEMO_MAX = 500;

    /** 그 가게 손님별 집계. :store · :now · :atRiskBefore를 받는다. 등급 규칙은 이 한 곳에만 있다 */
    private static final String SUMMARY = """
            SELECT r.customer_id, c.type, c.name, c.purged_at,
                   SUM(r.status = 'visited') AS visit_count,
                   MAX(r.visited_at) AS last_visited_at,
                   SUM(r.status = 'visited' AND r.course_proposal_id IS NOT NULL) AS recommended_visit_count,
                   MAX(r.start_at) AS last_reserved_at,
                   CASE
                       WHEN SUM(r.status = 'visited') = 0 THEN NULL
                       WHEN SUM(r.status = 'visited') = 1 THEN 'new'
                       WHEN SUM(r.status = 'visited') = 2 THEN 'regular'
                       WHEN MAX(r.visited_at) < :atRiskBefore THEN 'at_risk'
                       ELSE 'loyal'
                   END AS grade
            FROM reservation r JOIN customer c ON c.id = r.customer_id
            WHERE r.store_id = :store
            """;

    private final StoreService stores;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    CustomerCardService(StoreService stores, JdbcTemplate jdbc, NamedParameterJdbcTemplate named, Clock clock) {
        this.stores = stores;
        this.jdbc = jdbc;
        this.named = named;
        this.clock = clock;
    }

    /** 고객 목록(O-04): 우리 가게에 예약이 있는 손님, 최근 예약 순. grade가 있으면 그 등급만 */
    @Transactional(readOnly = true)
    public Page list(AccountPrincipal me, String grade, int page, int size) {
        long storeId = stores.requireApprovedOwnStore(me);
        MapSqlParameterSource p = params(storeId);
        String having = "";
        if (grade != null) {
            having = " HAVING grade = :grade";
            p.addValue("grade", grade);
        }
        String grouped = SUMMARY + " GROUP BY r.customer_id, c.type, c.name, c.purged_at" + having;
        Long total = named.queryForObject("SELECT COUNT(*) FROM (" + grouped + ") t", p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Summary> items = named.query(grouped + " ORDER BY last_reserved_at DESC, r.customer_id DESC LIMIT :limit OFFSET :offset",
                p, (rs, i) -> summary(rs));
        return new Page(items, total == null ? 0 : total);
    }

    /** 고객 카드(O-05): 우리 가게 방문 이력 · 메모 · 등급. 우리 가게에 예약이 없는 손님은 404 */
    @Transactional(readOnly = true)
    public Card card(AccountPrincipal me, long customerId) {
        long storeId = stores.requireApprovedOwnStore(me);
        Summary summary = summaryOf(storeId, customerId);
        // 메모 칸이 NULL인 행도 있어 Stream.findFirst(null 요소에서 NPE)를 쓰지 않는다
        List<String> memos = summary.purged() ? List.of() : jdbc.queryForList(
                "SELECT memo FROM customer_card WHERE store_id = ? AND customer_id = ?", String.class, storeId, customerId);
        String memo = memos.isEmpty() ? null : memos.get(0);
        List<Visit> visits = jdbc.query("""
                SELECT id, reservation_no, start_at, visited_at, party_size, visit_amount, course_proposal_id
                FROM reservation WHERE store_id = ? AND customer_id = ? AND status = 'visited'
                ORDER BY visited_at DESC, id DESC
                """, (rs, i) -> new Visit(String.valueOf(rs.getLong("id")), rs.getString("reservation_no"),
                        instant(rs.getObject("start_at", LocalDateTime.class)),
                        instant(rs.getObject("visited_at", LocalDateTime.class)), rs.getInt("party_size"),
                        (Integer) rs.getObject("visit_amount"), rs.getObject("course_proposal_id") != null),
                storeId, customerId);
        return new Card(summary, memo, visits);
    }

    /** 메모 쓰기 · 고치기(FR-CUS-03). 빈 값이면 지운다. 파기된 비회원에게는 쓸 수 없다(409) */
    @Transactional
    public Card writeMemo(AccountPrincipal me, long customerId, String memo) {
        long storeId = stores.requireApprovedOwnStore(me);
        Summary summary = summaryOf(storeId, customerId);
        if (summary.purged()) {
            throw new ApiException(ErrorCode.CONFLICT, "파기된 손님 정보에는 메모를 남길 수 없습니다.");
        }
        String value = memo == null || memo.isBlank() ? null : memo.strip();
        if (value != null && value.codePointCount(0, value.length()) > MEMO_MAX) {
            throw new ApiException(ErrorCode.INVALID);
        }
        jdbc.update("""
                INSERT INTO customer_card (store_id, customer_id, memo) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE memo = VALUES(memo)
                """, storeId, customerId, value);
        return card(me, customerId);
    }

    /** D3 · D4 예약 응답 등이 쓴다: 그 가게 기준 손님별 등급(방문 처리 없으면 빠짐) */
    @Transactional(readOnly = true)
    public Map<Long, String> grades(long storeId, Collection<Long> customerIds) {
        Map<Long, String> grades = new HashMap<>();
        if (customerIds.isEmpty()) {
            return grades;
        }
        MapSqlParameterSource p = params(storeId).addValue("ids", customerIds);
        named.query(SUMMARY + " AND r.customer_id IN (:ids) GROUP BY r.customer_id, c.type, c.name, c.purged_at", p, rs -> {
            String grade = rs.getString("grade");
            if (grade != null) {
                grades.put(rs.getLong("customer_id"), grade);
            }
        });
        return grades;
    }

    private Summary summaryOf(long storeId, long customerId) {
        List<Summary> rows = named.query(SUMMARY + " AND r.customer_id = :customer GROUP BY r.customer_id, c.type, c.name, c.purged_at",
                params(storeId).addValue("customer", customerId), (rs, i) -> summary(rs));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND); // 우리 가게 손님이 아니면 존재를 숨긴다
        }
        return rows.get(0);
    }

    private MapSqlParameterSource params(long storeId) {
        Instant now = Instant.now(clock);
        return new MapSqlParameterSource("store", storeId)
                .addValue("atRiskBefore", LocalDateTime.ofInstant(now.minus(AT_RISK_AFTER), ZoneOffset.UTC));
    }

    private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean purged = rs.getObject("purged_at") != null;
        return new Summary(String.valueOf(rs.getLong("customer_id")), rs.getString("type"),
                purged ? null : NameMask.mask(rs.getString("name")), rs.getString("grade"),
                rs.getInt("visit_count"), instant(rs.getObject("last_visited_at", LocalDateTime.class)),
                rs.getInt("recommended_visit_count"), purged);
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    /**
     * 손님 한 명(우리 가게 기준).
     *
     * @param grade 방문 처리가 없으면 null
     * @param recommendedVisitCount 정식 코스 제안으로 와서 방문 처리된 횟수(보낸 가게는 없음)
     * @param purged 파기된 비회원이면 true(이름 · 메모 없음, FR-CUS-07)
     */
    public record Summary(String customerId, String customerType, String maskedName, String grade, int visitCount,
                          Instant lastVisitedAt, int recommendedVisitCount, boolean purged) {
    }

    /** @param fromRecommendation 정식 코스 제안으로 온 예약(사장님 예약 목록과 같은 뜻) */
    public record Visit(String reservationId, String reservationNo, Instant startAt, Instant visitedAt, int partySize,
                        Integer visitAmount, boolean fromRecommendation) {
    }

    public record Card(Summary customer, String memo, List<Visit> visits) {
    }

    public record Page(List<Summary> items, long totalElements) {
    }
}
