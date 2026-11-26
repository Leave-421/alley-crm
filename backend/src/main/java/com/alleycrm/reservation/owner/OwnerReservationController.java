package com.alleycrm.reservation.owner;

import com.alleycrm.reservation.ReservationResponse;
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 예약: 승인 · 거절(D3), 날짜별 목록(D4). 계약: CONTRACTS_API 「사장님 예약」. */
@RestController
class OwnerReservationController {

    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationStateService states;
    private final OwnerStoreResolver owners;
    private final JdbcTemplate jdbc;

    OwnerReservationController(ReservationStateService states, OwnerStoreResolver owners, JdbcTemplate jdbc) {
        this.states = states;
        this.owners = owners;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/owner/reservations/{id}/confirm")
    ReservationResponse confirm(@PathVariable long id, Authentication auth) {
        return ReservationResponse.of(states.confirm(owners.storeIdOf(auth), id), null);
    }

    @PostMapping("/api/v1/owner/reservations/{id}/reject")
    ReservationResponse reject(@PathVariable long id, Authentication auth) {
        return ReservationResponse.of(states.reject(owners.storeIdOf(auth), id), null);
    }

    /** 방문 처리(D11): 결제 금액(쿠폰 할인 전)을 받는다 */
    @PostMapping("/api/v1/owner/reservations/{id}/visit")
    ReservationResponse visit(@PathVariable long id, @RequestBody VisitRequest body, Authentication auth) {
        return ReservationResponse.of(states.visit(owners.storeIdOf(auth), id, body == null ? null : body.visitAmount()), null);
    }

    record VisitRequest(Integer visitAmount) {
    }

    /** 운영 시간대 기준 그 날짜에 시작하는 내 가게 예약, 시각 순. 하루치라 페이지를 나누지 않는다 */
    @GetMapping("/api/v1/owner/reservations")
    Map<String, Object> byDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        long storeId = owners.storeIdOf(auth);
        LocalDateTime from = utc(date.atStartOfDay(SERVICE_ZONE).toInstant());
        LocalDateTime to = utc(date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant());
        List<OwnerReservationItem> items = jdbc.query("""
                SELECT r.id, r.reservation_no, r.start_at, r.party_size, r.status, r.cancel_reason, r.requested_at,
                       r.request_note, r.deposit_amount, r.course_proposal_id, c.type AS customer_type, c.name
                FROM reservation r JOIN customer c ON c.id = r.customer_id
                WHERE r.store_id = ? AND r.start_at >= ? AND r.start_at < ?
                ORDER BY r.start_at, r.id
                """, (rs, i) -> new OwnerReservationItem(
                String.valueOf(rs.getLong("id")),
                rs.getString("reservation_no"),
                instant(rs.getObject("start_at", LocalDateTime.class)),
                rs.getInt("party_size"),
                rs.getString("status"),
                rs.getString("cancel_reason"),
                instant(rs.getObject("requested_at", LocalDateTime.class)),
                rs.getString("request_note"),
                rs.getInt("deposit_amount"),
                rs.getString("customer_type"),
                NameMask.mask(rs.getString("name")),
                rs.getObject("course_proposal_id") != null), storeId, from, to);
        return Map.of("date", date.toString(), "items", items);
    }

    /**
     * 사장님 목록 한 줄. 손님 이름은 가린 값만, 전화번호 · 보낸 가게는 넣지 않는다(FR-CUS-05, FR-RSV-04).
     */
    record OwnerReservationItem(
            String id,
            String reservationNo,
            Instant startAt,
            int partySize,
            String status,
            String cancelReason,
            Instant requestedAt,
            String requestNote,
            int depositAmount,
            String customerType,
            String maskedName,
            boolean fromRecommendation) {
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
