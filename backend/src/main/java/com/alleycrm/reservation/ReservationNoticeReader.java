package com.alleycrm.reservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 손님 알림(C14)이 예약을 읽는 곳. 읽기만 한다. */
@Service
@Transactional(readOnly = true)
public class ReservationNoticeReader {

    private final JdbcTemplate jdbc;

    ReservationNoticeReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 예약을 기준 가게 예약으로 둔 다음 코스 예약 중 살아 있는(신청 · 확정) 것(FR-NTF-03 #2) */
    public List<String> liveNextCourseIds(long rootReservationId) {
        return jdbc.queryForList("""
                SELECT id FROM reservation WHERE course_root_id = ? AND status IN (?, ?) ORDER BY start_at, id
                """, Long.class, rootReservationId, ReservationStatus.REQUESTED, ReservationStatus.CONFIRMED)
                .stream().map(String::valueOf).toList();
    }

    /** 방문 일시가 (from, to]인 확정 예약(리마인드 대상, FR-NTF-03 #3) */
    public List<Upcoming> confirmedStartingIn(Instant from, Instant to) {
        return jdbc.query("""
                SELECT id, customer_id FROM reservation WHERE status = ? AND start_at > ? AND start_at <= ? ORDER BY start_at, id
                """, (rs, i) -> new Upcoming(rs.getLong("id"), rs.getLong("customer_id")),
                ReservationStatus.CONFIRMED, utc(from), utc(to));
    }

    public record Upcoming(long reservationId, long customerId) {
    }

    /** DB 시각은 UTC 벽시계(hibernate.jdbc.time_zone=UTC) */
    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
