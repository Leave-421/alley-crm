package com.alleycrm.recommend.record;

import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.TimeBand;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 탐색 기록 · 정식 코스 제안 표(D19). 노출 · 제안은 유일 제약으로 중복을 거른다:
 * {@code ON DUPLICATE KEY UPDATE id = id}로 이미 있으면 아무것도 바꾸지 않고, 잠그는 읽기로 그 행을 다시 읽는다.
 * 잠그는 읽기라 동시에 다른 트랜잭션이 먼저 넣은 행도 보인다. 시각은 UTC 값이다.
 */
@Repository
class ExplorationRecordRepository {

    private final JdbcTemplate jdbc;

    ExplorationRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 손님에게 보이는 가게인지(승인된 가게). 예약 신청(D2)과 같은 기준. */
    boolean isVisibleStore(long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM store WHERE id = ? AND join_status = 'approved'", Integer.class, storeId);
        return count != null && count > 0;
    }

    /** 코스의 가게는 순서대로 JSON 숫자 배열로 둔다(06-data COURSE_PREVIEW_EVENT). */
    void insertCoursePreview(long visitorId, CoursePreviewType type, List<Long> storeIds, DayType dayType,
            TimeBand timeBand, LocalDateTime createdAt) {
        String json = storeIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        jdbc.update("""
                INSERT INTO course_preview_event (visitor_id, type, store_ids, day_type, time_band, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, visitorId, type.code(), json, dayType.code(), timeBand.code(), createdAt);
    }

    void insertStoreClick(long visitorId, long storeId, LocalDateTime clickedAt) {
        jdbc.update("INSERT INTO store_click (visitor_id, store_id, clicked_at) VALUES (?, ?, ?)",
                visitorId, storeId, clickedAt);
    }

    /** (방문자, 표시 ID, 추천 가게)당 1건. 이미 있으면 처음 행의 id. */
    long insertExposureIfAbsent(long visitorId, UUID displayId, long baseStoreId, ExposureItem item, int page,
            LocalDateTime shownAt) {
        jdbc.update("""
                INSERT INTO recommendation_exposure
                    (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, page, badge, reason_type, shown_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                visitorId, displayId.toString(), baseStoreId, item.storeId(), item.rank(), page,
                item.badge().code(), item.reasonType() == null ? null : item.reasonType().code(), shownAt);
        return jdbc.queryForObject("""
                SELECT id FROM recommendation_exposure
                WHERE visitor_id = ? AND display_id = ? AND recommended_store_id = ?
                FOR SHARE
                """, Long.class, visitorId, displayId.toString(), item.storeId());
    }

    /**
     * 노출이 이 브라우저의 방문자({@code browserVisitorId}, 없으면 null)나 이 손님에 이어진 방문자의 것일 때만 찾는다.
     * 다른 사람의 노출은 없는 것과 같다(IDOR).
     */
    Optional<ExposureSource> findExposureOwnedBy(long exposureId, Long browserVisitorId, long customerId) {
        List<ExposureSource> rows = jdbc.query("""
                SELECT e.id, e.base_store_id, e.recommended_store_id
                FROM recommendation_exposure e
                JOIN visitor v ON v.id = e.visitor_id
                WHERE e.id = ? AND (v.id = ? OR v.customer_id = ?)
                """,
                (rs, n) -> new ExposureSource(
                        rs.getLong("id"), rs.getLong("base_store_id"), rs.getLong("recommended_store_id")),
                exposureId, browserVisitorId, customerId);
        return rows.stream().findFirst();
    }

    /** (기준 예약, 추천 가게)당 1건. 이미 있으면 처음 행(처음 표시 시각 그대로). */
    RecordedProposal insertProposalIfAbsent(long baseReservationId, long storeId, LocalDateTime shownAt) {
        jdbc.update("""
                INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, baseReservationId, storeId, shownAt);
        return jdbc.queryForObject("""
                SELECT id, recommended_store_id, first_shown_at FROM course_proposal
                WHERE base_reservation_id = ? AND recommended_store_id = ?
                FOR SHARE
                """,
                (rs, n) -> new RecordedProposal(
                        rs.getLong("recommended_store_id"),
                        rs.getLong("id"),
                        rs.getObject("first_shown_at", LocalDateTime.class)),
                baseReservationId, storeId);
    }
}
