package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지연 입력(D14, FR-RSV-10 #2). 예약 시각은 바꾸지 않는다. 리마인드(#1)는 C14 `ReservationReminderJob`이 한다.
 * 사장님 알림은 {@link DelayNoticeListener}가 {@link DelayReportedEvent}를 커밋 뒤에 받아 남긴다.
 */
@Service
public class DelayService {

    /** 지연 입력 범위(분) */
    static final int MAX_DELAY_MINUTES = 120;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DelayService(JdbcTemplate jdbc, Clock clock, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /**
     * 손님 지연 입력: 예상 도착 = 예약 시각 + delayMinutes(1 ~ 120). 확정 예약, 예약 시각 2시간 뒤까지만.
     * 다시 입력하면 새 값으로 바꾼다. 다른 손님 예약은 404.
     */
    @Transactional
    public Instant reportDelay(long customerId, long reservationId, Integer delayMinutes) {
        if (delayMinutes == null || delayMinutes < 1 || delayMinutes > MAX_DELAY_MINUTES) {
            throw new ApiException(ErrorCode.INVALID);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT store_id, customer_id, start_at, status FROM reservation WHERE id = ? FOR UPDATE", reservationId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("customer_id")).longValue() != customerId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);
        Instant start = ((LocalDateTime) r.get("start_at")).toInstant(ZoneOffset.UTC);
        Instant now = Instant.now(clock);
        if (!ReservationStatus.CONFIRMED.equals(r.get("status")) || now.isAfter(start.plus(Duration.ofMinutes(MAX_DELAY_MINUTES)))) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Instant eta = start.plus(Duration.ofMinutes(delayMinutes));
        jdbc.update("UPDATE reservation SET delay_eta = ? WHERE id = ?", LocalDateTime.ofInstant(eta, ZoneOffset.UTC), reservationId);
        events.publishEvent(new DelayReportedEvent(reservationId, ((Number) r.get("store_id")).longValue(), customerId, start, eta));
        return eta;
    }
}
