package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 상태를 바꾸는 단 하나의 곳(D3 승인 · 거절 · 자동 거절, D4 자동 취소).
 * 바꿀 때마다 {@link ReservationStatusChangedEvent}를 내보낸다. 자동 작업의 실행 주기는 D7(스케줄)이 정한다.
 */
@Service
public class ReservationStateService {

    /** 서비스 운영 시간대(05-policy). '예약일 다음 날'을 정한다 */
    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final com.alleycrm.setting.SettingService settings;

    public ReservationStateService(ReservationRepository reservations, JdbcTemplate jdbc, Clock clock,
            ApplicationEventPublisher events, com.alleycrm.setting.SettingService settings) {
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
        this.settings = settings;
    }

    /** 사장님 승인: 신청 → 확정(FR-RSV-03 #1). 다른 가게 예약은 없는 것처럼 404 */
    @Transactional
    public Reservation confirm(long storeId, long reservationId) {
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.REQUESTED);
        if (!r.getStartAt().isAfter(now())) {
            // 예약 시각이 지난 신청은 자동 거절 대상이라 승인할 수 없다
            throw new ApiException(ErrorCode.CONFLICT);
        }
        r.confirm(now());
        publish(r, ReservationStatus.REQUESTED, false);
        return r;
    }

    /**
     * 손님 취소(D6, FR-RSV-06): 신청 · 확정 예약만. 다른 손님 예약은 없는 것처럼 404(#5).
     * <ul>
     *   <li>예약 시각 24시간 전까지: 100% 환불(#1 · #4, 가게 마감과 상관없이 보장)</li>
     *   <li>그 뒤 가게 취소 마감(예약 시각 − cancel_deadline_hours) 전: 가게 비율, 없으면 권역 설정값(#2). 1원 미만 버림</li>
     *   <li>마감이 지남: 거절(#3, conflict)</li>
     * </ul>
     * 환불 · 쿠폰 적용 취소 · 알림은 상태 변경 이벤트를 받는 쪽이 한다.
     */
    @Transactional
    public Reservation cancelByCustomer(long customerId, long reservationId) {
        Reservation r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (r.getCustomerId() != customerId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (!ReservationStatus.REQUESTED.equals(r.getStatus()) && !ReservationStatus.CONFIRMED.equals(r.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Instant now = now();
        String from = r.getStatus();
        int refund;
        if (!now.isAfter(r.getStartAt().minus(java.time.Duration.ofHours(24)))) {
            refund = r.getDepositAmount();
        } else {
            java.util.Map<String, Object> s = jdbc.queryForMap(
                    "SELECT region_id, cancel_deadline_hours, late_cancel_refund_rate FROM store WHERE id = ?", r.getStoreId());
            Instant deadline = r.getStartAt().minus(java.time.Duration.ofHours(((Number) s.get("cancel_deadline_hours")).longValue()));
            if (!now.isBefore(deadline)) {
                throw new ApiException(ErrorCode.CONFLICT);
            }
            java.math.BigDecimal rate = s.get("late_cancel_refund_rate") != null
                    ? (java.math.BigDecimal) s.get("late_cancel_refund_rate")
                    : settings.getDecimal(((Number) s.get("region_id")).longValue(),
                            com.alleycrm.setting.SettingKey.LATE_CANCEL_REFUND_RATE);
            refund = rate.multiply(java.math.BigDecimal.valueOf(r.getDepositAmount()))
                    .setScale(0, java.math.RoundingMode.DOWN).intValueExact();
        }
        r.cancelByCustomer(refund, now);
        reservations.saveAndFlush(r); // 리스너가 DB에서 refund_amount를 읽는다
        publish(r, from, false);
        return r;
    }

    /** 사장님 거절: 신청 → 취소(가게 거절)(FR-RSV-03 #2). 환불 · 쿠폰은 이벤트를 받는 쪽이 한다 */
    @Transactional
    public Reservation reject(long storeId, long reservationId) {
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.REQUESTED);
        r.cancel(CancelReason.STORE_REJECTED, now());
        publish(r, ReservationStatus.REQUESTED, false);
        return r;
    }

    /** 방문 금액 상한(원). 입력 실수를 막는 값이다 */
    static final int MAX_VISIT_AMOUNT = 10_000_000;

    /**
     * 사장님 방문 처리: 확정 → 방문(FR-RSV-09 #1). 결제 금액(쿠폰 할인 전)이 없으면 거절(#2).
     * 상태 변경 이벤트와 '방문 처리됨' 이벤트를 같은 트랜잭션에서 내보낸다. 쿠폰은 건드리지 않는다(#3).
     */
    @Transactional
    public Reservation visit(long storeId, long reservationId, Integer visitAmount) {
        if (visitAmount == null || visitAmount < 0 || visitAmount > MAX_VISIT_AMOUNT) {
            throw new ApiException(ErrorCode.INVALID);
        }
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.CONFIRMED);
        Instant now = now();
        r.visit(visitAmount, now);
        publish(r, ReservationStatus.CONFIRMED, false);
        events.publishEvent(new VisitProcessedEvent(r.getId(), r.getStoreId(), r.getCustomerId(), visitAmount, now,
                r.getCourseProposalId(), r.getSenderStoreId()));
        return r;
    }

    /**
     * 자동 거절(FR-RSV-03 #3): 예약 시각이 된 신청 예약 → 취소(가게 거절). D7이 주기적으로 부른다.
     *
     * @return 바꾼 건수
     */
    @Transactional
    public int autoRejectDue() {
        Instant now = now();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM reservation WHERE status = ? AND start_at <= ? ORDER BY id
                """, Long.class, ReservationStatus.REQUESTED, utc(now));
        int n = 0;
        for (Long id : ids) {
            Reservation r = reservations.findForUpdate(id).orElseThrow();
            // 잠그는 사이에 사장님이 처리했으면 건너뛴다
            if (ReservationStatus.REQUESTED.equals(r.getStatus())) {
                r.cancel(CancelReason.STORE_REJECTED, now);
                publish(r, ReservationStatus.REQUESTED, true);
                n++;
            }
        }
        return n;
    }

    /**
     * 자동 취소(FR-RSV-02 #1): 확정 예약이 예약일 다음 날이 끝날 때까지 방문 처리 · 취소 없음 → 취소(자동 취소).
     * 운영 시간대 기준 '예약일 + 2일 00:00'이 지나면 대상이다. D7이 주기적으로 부른다.
     *
     * @return 바꾼 건수
     */
    @Transactional
    public int autoCancelDue() {
        Instant now = now();
        // start_at < (오늘 - 1일) 00:00 → 예약일이 그저께 이전 = 예약일 다음 날(어제)이 이미 끝남
        LocalDate today = LocalDate.ofInstant(now, SERVICE_ZONE);
        Instant cutoff = today.minusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM reservation WHERE status = ? AND start_at < ? ORDER BY id
                """, Long.class, ReservationStatus.CONFIRMED, utc(cutoff));
        int n = 0;
        for (Long id : ids) {
            Reservation r = reservations.findForUpdate(id).orElseThrow();
            if (ReservationStatus.CONFIRMED.equals(r.getStatus())) {
                r.cancel(CancelReason.AUTO_CANCELED, now);
                publish(r, ReservationStatus.CONFIRMED, true);
                n++;
            }
        }
        return n;
    }

    private Reservation lockOwn(long storeId, long reservationId) {
        Reservation r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (r.getStoreId() != storeId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    private static void requireStatus(Reservation r, String status) {
        if (!status.equals(r.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
    }

    private void publish(Reservation r, String from, boolean byScheduler) {
        events.publishEvent(new ReservationStatusChangedEvent(r.getId(), r.getStoreId(), r.getCustomerId(),
                from, r.getStatus(), r.getCancelReason(), byScheduler));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** DB 시각은 UTC 벽시계(hibernate.jdbc.time_zone=UTC) */
    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
