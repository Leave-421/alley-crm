package com.alleycrm.reservation;

import static com.alleycrm.common.time.ServiceTime.utc;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.ServiceTime;
import com.alleycrm.reservation.request.SlotRules;
import com.alleycrm.reservation.request.StoreSlotReader;
import com.alleycrm.reservation.request.StoreSnapshot;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import com.alleycrm.visitreport.VisitReport;
import com.alleycrm.visitreport.VisitReportService;
import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    private final ReservationRepository reservations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final SettingService settings;
    private final StoreSlotReader slots;
    private final WalletService wallets;
    private final VisitReportService visitReports;

    public ReservationStateService(ReservationRepository reservations, JdbcTemplate jdbc, Clock clock,
            ApplicationEventPublisher events, SettingService settings,
            StoreSlotReader slots, WalletService wallets, VisitReportService visitReports) {
        this.visitReports = visitReports;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
        this.settings = settings;
        this.slots = slots;
        this.wallets = wallets;
    }

    /** 사장님 승인: 신청 → 확정(FR-RSV-03 #1). 다른 가게 예약은 없는 것처럼 404 */
    @Transactional
    public Reservation confirm(long storeId, long reservationId) {
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.REQUESTED);
        if (r.hasPendingChange()) {
            return approveChange(r); // 변경 건(D8)
        }
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
        Integer refund = customerCancelRefund(r.getStoreId(), r.getStartAt(), r.getDepositAmount(), now);
        if (refund == null) {
            throw new ApiException(ErrorCode.CONFLICT); // 가게 취소 마감이 지남(#3)
        }
        r.cancelByCustomer(refund, now);
        reservations.saveAndFlush(r); // 리스너가 DB에서 refund_amount를 읽는다
        publish(r, from, false);
        return r;
    }

    /**
     * 지금 손님이 취소하면 돌려받을 예약금(D6 규칙). 마감이 지나 취소할 수 없으면 null.
     * 내 예약 목록(D13)이 '취소할 때 환불 금액' 안내에 같이 쓴다. 상태는 보지 않는다(부르는 쪽이 신청 · 확정만).
     */
    public Integer customerCancelRefund(long storeId, Instant startAt, int depositAmount, Instant now) {
        if (!now.isAfter(startAt.minus(Duration.ofHours(24)))) {
            return depositAmount;
        }
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT region_id, cancel_deadline_hours, late_cancel_refund_rate FROM store WHERE id = ?", storeId);
        Instant deadline = startAt.minus(Duration.ofHours(((Number) s.get("cancel_deadline_hours")).longValue()));
        if (!now.isBefore(deadline)) {
            return null;
        }
        BigDecimal rate = s.get("late_cancel_refund_rate") != null
                ? (BigDecimal) s.get("late_cancel_refund_rate")
                : settings.getDecimal(((Number) s.get("region_id")).longValue(),
                        SettingKey.LATE_CANCEL_REFUND_RATE);
        return rate.multiply(BigDecimal.valueOf(depositAmount))
                .setScale(0, RoundingMode.DOWN).intValueExact();
    }

    /** 사장님 거절: 신청 → 취소(가게 거절)(FR-RSV-03 #2). 환불 · 쿠폰은 이벤트를 받는 쪽이 한다 */
    @Transactional
    public Reservation reject(long storeId, long reservationId) {
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.REQUESTED);
        if (r.hasPendingChange()) {
            // 변경 거절: 원래 예약으로 확정 유지(FR-RSV-07 #3), 돈은 그대로
            r.revertChange();
            publish(r, ReservationStatus.REQUESTED, false);
            return r;
        }
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
                r.getCourseProposalId(), r.getSenderStoreId(), now));
        return r;
    }

    /**
     * 늦은 방문 처리(D23, FR-STL-09 #2 · #4, FR-RSV-02 #4): 자동 취소된 예약에 상인회가 '맞음'으로 기록한 미처리 신고가
     * 있을 때만 취소 → 방문. 소개 수수료 · 쿠폰 되돌리기 · 방문 금액 알림은 방문 처리와 같이 이벤트로 진행된다.
     * 정산 달은 원래 예약 달(#3). 예약금은 자동 취소 때 돌려주지 않았으므로 움직이지 않는다.
     */
    @Transactional
    public Reservation lateVisit(long storeId, long reservationId, Integer visitAmount) {
        if (visitAmount == null || visitAmount < 0 || visitAmount > MAX_VISIT_AMOUNT) {
            throw new ApiException(ErrorCode.INVALID);
        }
        Reservation r = lockOwn(storeId, reservationId);
        requireStatus(r, ReservationStatus.CANCELED);
        if (!CancelReason.AUTO_CANCELED.equals(r.getCancelReason())
                || !visitReports.hasValidReport(reservationId, VisitReport.TYPE_NOT_PROCESSED)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Instant now = now();
        r.lateVisit(visitAmount, now);
        publish(r, ReservationStatus.CANCELED, false);
        events.publishEvent(new VisitProcessedEvent(r.getId(), r.getStoreId(), r.getCustomerId(), visitAmount, now,
                r.getCourseProposalId(), r.getSenderStoreId(), r.getStartAt()));
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
                SELECT id FROM reservation
                WHERE status = ? AND (start_at <= ? OR pending_start_at <= ?) ORDER BY id
                """, Long.class, ReservationStatus.REQUESTED, utc(now), utc(now));
        int n = 0;
        for (Long id : ids) {
            Reservation r = reservations.findForUpdate(id).orElseThrow();
            // 잠그는 사이에 사장님이 처리했으면 건너뛴다
            if (!ReservationStatus.REQUESTED.equals(r.getStatus())) {
                continue;
            }
            if (r.hasPendingChange()) {
                // 변경만 자동 거절: 원래 시각과 바뀐 시각 중 이른 쪽까지 승인 없음 → 원래 예약 유지(FR-RSV-07 #4)
                if (!changeDeadline(r).isAfter(now)) {
                    r.revertChange();
                    publish(r, ReservationStatus.REQUESTED, true);
                    n++;
                }
                continue;
            }
            if (!r.getStartAt().isAfter(now)) {
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
        LocalDate today = LocalDate.ofInstant(now, ServiceTime.ZONE);
        Instant cutoff = today.minusDays(1).atStartOfDay(ServiceTime.ZONE).toInstant();
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

    /**
     * 손님 예약 변경(D8, FR-RSV-07 #1, 기획 11-27): 확정 예약만, 가게 취소 마감 전까지. 날짜 · 인원 중 바뀐 값이 대기하고
     * 상태는 '신청'이 된다(원래 값은 그대로). 새 시각은 예약 신청과 같은 규칙(영업시간 · 휴무일 · 기간 · 자리 · 최대 인원)으로 확인한다.
     * 인원이 늘어 회원이 더 내야 하면 지금 잔액을 확인한다(모자라면 insufficient_balance). 돈은 승인 때 정산한다.
     */
    @Transactional
    public Reservation requestChange(long customerId, long reservationId, Instant newStart, Integer newParty) {
        Reservation r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (r.getCustomerId() != customerId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (!ReservationStatus.CONFIRMED.equals(r.getStatus()) || r.hasPendingChange()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Instant now = now();
        if (customerCancelRefund(r.getStoreId(), r.getStartAt(), r.getDepositAmount(), now) == null) {
            throw new ApiException(ErrorCode.CONFLICT); // 가게 취소 마감이 지남(11-27)
        }
        Instant start = newStart == null ? r.getStartAt() : newStart;
        int party = newParty == null ? r.getPartySize() : newParty;
        if (party < 1 || (start.equals(r.getStartAt()) && party == r.getPartySize())) {
            throw new ApiException(ErrorCode.INVALID); // 바뀐 값이 없음
        }
        StoreSnapshot store = checkSlot(r, start, party, now);
        int extra = store.depositPerPerson() * party - r.getDepositAmount();
        if (extra > 0 && isMember(r.getCustomerId())
                && wallets.balance(WalletOwner.customer(r.getCustomerId())) < extra) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        r.requestChange(start, party);
        publish(r, ReservationStatus.CONFIRMED, false);
        return r;
    }

    /** 변경 승인(#2): 자리를 다시 확인하고 예약금 차액을 정산한 뒤 바꾼 값으로 확정 */
    private Reservation approveChange(Reservation r) {
        Instant now = now();
        if (!changeDeadline(r).isAfter(now)) {
            throw new ApiException(ErrorCode.CONFLICT); // 이미 자동 거절 대상
        }
        StoreSnapshot store = checkSlot(r, r.getPendingStartAt(), r.getPendingPartySize(), now);
        int newDeposit = store.depositPerPerson() * r.getPendingPartySize();
        int diff = newDeposit - r.getDepositAmount();
        if (diff != 0) {
            settleDeposit(r, diff);
        }
        r.approveChange(newDeposit, now);
        publish(r, ReservationStatus.REQUESTED, false);
        return r;
    }

    /** 원래 시각과 바뀐 시각 중 이른 쪽(이때까지 승인이 없으면 변경만 자동 거절) */
    private static Instant changeDeadline(Reservation r) {
        return r.getStartAt().isBefore(r.getPendingStartAt()) ? r.getStartAt() : r.getPendingStartAt();
    }

    /** 새 시각 · 인원이 예약 신청 규칙에 맞는지(D2 · D5와 같은 규칙). 자기 예약이 이미 그 시각이면 자리 계산에서 뺀다 */
    private StoreSnapshot checkSlot(Reservation r, Instant start, int party, Instant now) {
        StoreSnapshot store = slots.lock(r.getStoreId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (store.maxPartySize() != null && party > store.maxPartySize()) {
            throw new ApiException(ErrorCode.INVALID);
        }
        LocalDate today = LocalDate.ofInstant(now, ServiceTime.ZONE);
        LocalDate day = LocalDate.ofInstant(start, ServiceTime.ZONE);
        boolean taken = slots.taken(store.id(), start) - (start.equals(r.getStartAt()) ? 1 : 0) >= store.capacityPerSlot();
        if (!"open".equals(store.businessStatus()) || !store.reservationEnabled() || !start.isAfter(now)
                || day.isAfter(today.plusDays(store.bookingWindowDays()))
                || !SlotRules.isSlotStart(store, start) || taken) {
            throw new ApiException(ErrorCode.SLOT_UNAVAILABLE);
        }
        return store;
    }

    /** 예약금 차액 정산(11-27): diff > 0이면 더 내고, < 0이면 돌려받는다 */
    private void settleDeposit(Reservation r, int diff) {
        String type = diff > 0 ? WalletTxType.DEPOSIT_PAID : WalletTxType.DEPOSIT_REFUNDED;
        if (isMember(r.getCustomerId())) {
            wallets.post(WalletOwner.customer(r.getCustomerId()), type, -diff, r.getId());
        } else {
            String phone = jdbc.queryForObject("SELECT phone FROM customer WHERE id = ?", String.class, r.getCustomerId());
            wallets.recordGuest(phone, type, -diff, r.getId());
        }
    }

    private boolean isMember(long customerId) {
        return "member".equals(jdbc.queryForObject("SELECT type FROM customer WHERE id = ?", String.class, customerId));
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

    /** DB 시각은 UTC 벽시계(hibernate.jdbc.time_zone=UTC) */}
