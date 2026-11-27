package com.alleycrm.reservation.request;

import static com.alleycrm.common.time.ServiceTime.utc;

import com.alleycrm.reservation.ReservationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 가게 예약 설정과 슬롯별 찬 자리를 읽는다(예약 신청 단계 30 · 예약 가능 시간 조회가 같이 씀). */
@Component
public class StoreSlotReader {

    private static final String STORE_COLUMNS = """
            SELECT id, region_id, join_status, business_status, reservation_enabled, open_time, close_time,
                   closed_days, slot_minutes, capacity_per_slot, booking_window_days, deposit_per_person,
                   max_party_size
            FROM store WHERE id = ?""";

    private final JdbcTemplate jdbc;

    public StoreSlotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 신청 처리용: 가게 행을 잠근다(같은 가게 신청을 한 줄로 세움) */
    public Optional<StoreSnapshot> lock(long storeId) {
        return first(jdbc.query(STORE_COLUMNS + " FOR UPDATE", (rs, i) -> map(rs), storeId));
    }

    public Optional<StoreSnapshot> read(long storeId) {
        return first(jdbc.query(STORE_COLUMNS, (rs, i) -> map(rs), storeId));
    }

    /** 같은 시작 시각의 신청 · 확정 예약 수 */
    public int taken(long storeId, Instant startAt) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE store_id = ? AND start_at = ? AND status IN (?, ?)
                """, Integer.class, storeId, utc(startAt), ReservationStatus.REQUESTED, ReservationStatus.CONFIRMED);
        return n == null ? 0 : n;
    }

    /** [from, to) 구간의 시작 시각별 신청 · 확정 예약 수 */
    public Map<Instant, Integer> takenBetween(long storeId, Instant from, Instant to) {
        Map<Instant, Integer> counts = new HashMap<>();
        jdbc.query("""
                SELECT start_at, COUNT(*) AS n FROM reservation
                WHERE store_id = ? AND start_at >= ? AND start_at < ? AND status IN (?, ?)
                GROUP BY start_at
                """, rs -> {
            counts.put(rs.getObject("start_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getInt("n"));
        }, storeId, utc(from), utc(to), ReservationStatus.REQUESTED, ReservationStatus.CONFIRMED);
        return counts;
    }

    private static StoreSnapshot map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoreSnapshot(
                rs.getLong("id"),
                rs.getLong("region_id"),
                rs.getString("join_status"),
                rs.getString("business_status"),
                rs.getBoolean("reservation_enabled"),
                rs.getObject("open_time", LocalTime.class),
                rs.getObject("close_time", LocalTime.class),
                rs.getString("closed_days"),
                rs.getInt("slot_minutes"),
                rs.getInt("capacity_per_slot"),
                rs.getInt("booking_window_days"),
                rs.getInt("deposit_per_person"),
                (Integer) rs.getObject("max_party_size"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** DB 시각은 UTC 벽시계(hibernate.jdbc.time_zone=UTC) */}
