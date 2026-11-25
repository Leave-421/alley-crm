package com.alleycrm.reservation.request;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.reservation.ReservationStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 30 예약 가능 확인 (D2 · D5).
 * D2가 맡는 것: 가게 존재 · 상태, 지난 시각, 예약 가능 기간, 슬롯 자리(동시에 받는 예약 수).
 * D5가 이 클래스에 더하는 것: 영업시간 · 휴무일 · 슬롯 단위 맞춤 · 한 팀 최대 인원.
 *
 * <p>같은 가게의 신청은 가게 행을 잠가(FOR UPDATE) 한 줄로 세운다. 동시에 들어와도 자리를 넘겨 받지 않는다.
 */
@Component
class AvailabilityStep implements ReservationRequestStep {

    /** 서비스 운영 시간대(05-policy). 예약 가능 기간의 '오늘'을 정한다 */
    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    AvailabilityStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return AVAILABILITY;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        StoreSnapshot store = lockStore(ctx.getStoreId());
        // 승인되지 않은 가게는 손님에게 없는 가게다
        if (!"approved".equals(store.joinStatus())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        ctx.setStore(store);

        if (!"open".equals(store.businessStatus()) || !store.reservationEnabled()) {
            throw slotUnavailable();
        }
        if (!ctx.getStartAt().isAfter(ctx.getRequestedAt())) {
            throw slotUnavailable();
        }
        // 예약 가능 기간: 운영 시간대 기준 오늘부터 booking_window_days일 뒤 날짜까지(FR-RSV-01 #4)
        LocalDate today = LocalDate.ofInstant(ctx.getRequestedAt(), SERVICE_ZONE);
        LocalDate day = LocalDate.ofInstant(ctx.getStartAt(), SERVICE_ZONE);
        if (day.isAfter(today.plusDays(store.bookingWindowDays()))) {
            throw slotUnavailable();
        }
        // DB 시각은 UTC 벽시계로 저장된다(hibernate.jdbc.time_zone=UTC). JVM 시간대와 상관없게 LocalDateTime(UTC)으로 비교한다.
        // 슬롯 자리: 같은 시작 시각의 신청 · 확정 예약 수(FR-RSV-01 #3, FR-RSV-05 #2)
        Integer taken = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE store_id = ? AND start_at = ? AND status IN (?, ?)
                """, Integer.class, store.id(), LocalDateTime.ofInstant(ctx.getStartAt(), ZoneOffset.UTC),
                ReservationStatus.REQUESTED, ReservationStatus.CONFIRMED);
        if (taken != null && taken >= store.capacityPerSlot()) {
            throw slotUnavailable();
        }
    }

    private StoreSnapshot lockStore(long storeId) {
        List<StoreSnapshot> rows = jdbc.query("""
                SELECT id, region_id, join_status, business_status, reservation_enabled, open_time, close_time,
                       closed_days, slot_minutes, capacity_per_slot, booking_window_days, deposit_per_person,
                       max_party_size
                FROM store WHERE id = ? FOR UPDATE
                """, (rs, i) -> new StoreSnapshot(
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
                (Integer) rs.getObject("max_party_size")), storeId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static ApiException slotUnavailable() {
        return new ApiException(ErrorCode.SLOT_UNAVAILABLE);
    }
}
