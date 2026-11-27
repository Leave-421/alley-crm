package com.alleycrm.reservation;

import static com.alleycrm.common.time.ServiceTime.instant;

import com.alleycrm.coupon.service.CouponApplyService;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 예약(D13, FR-RSV-12, G-04). '다음 코스로 예약하기'로 만든 예약은 기준 예약과 한 묶음(시간순)으로,
 * 따로 한 일반 예약은 혼자 한 묶음으로 준다(#3). 기준 예약이 취소 · 거절돼도 묶음은 그대로이고 그 예약의 상태가
 * canceled로 보인다(#1 · #2). 묶음은 가장 이른 예약 시각 순. 손님은 로그인 계정으로 정한다(회원).
 */
@RestController
class MyReservationsController {

    private final JdbcTemplate jdbc;
    private final CouponApplyService couponApply;
    private final com.alleycrm.coupon.service.CouponVisitService couponVisit;
    private final ReservationStateService states;
    private final Clock clock;
    private final MemberCustomerResolver members;

    MyReservationsController(JdbcTemplate jdbc, CouponApplyService couponApply, ReservationStateService states, Clock clock,
            com.alleycrm.coupon.service.CouponVisitService couponVisit, MemberCustomerResolver members) {
        this.members = members;
        this.couponVisit = couponVisit;
        this.jdbc = jdbc;
        this.couponApply = couponApply;
        this.states = states;
        this.clock = clock;
    }

    @GetMapping("/api/v1/reservations/mine")
    Map<String, Object> mine(Authentication auth) {
        long customerId = members.customerId(auth);
        Instant now = Instant.now(clock);
        List<MyReservation> rows = jdbc.query("""
                SELECT r.id, r.reservation_no, r.store_id, s.name AS store_name, r.start_at, r.party_size, r.status,
                       r.cancel_reason, r.requested_at, r.deposit_amount, r.refund_amount, r.visit_amount, r.visited_at,
                       r.course_root_id
                FROM reservation r JOIN store s ON s.id = r.store_id
                WHERE r.customer_id = ?
                ORDER BY r.start_at, r.id
                """, (rs, i) -> {
            long id = rs.getLong("id");
            long storeId = rs.getLong("store_id");
            Instant start = instant(rs.getObject("start_at", LocalDateTime.class));
            String status = rs.getString("status");
            int deposit = rs.getInt("deposit_amount");
            boolean cancelable = ReservationStatus.REQUESTED.equals(status) || ReservationStatus.CONFIRMED.equals(status);
            Integer refundIfCanceled = cancelable ? states.customerCancelRefund(storeId, start, deposit, now) : null;
            Object root = rs.getObject("course_root_id");
            Integer visitAmount = (Integer) rs.getObject("visit_amount");
            // 방문 처리된 예약: 방문 금액 알림(D21)과 같은 값(FR-STL-07 #1)
            Integer couponDiscount = visitAmount == null ? null : couponVisit.discountFor(id, visitAmount)
                    .map(com.alleycrm.coupon.dto.VisitCouponDiscount::discountAmount).orElse(0);
            return new MyReservation(
                    String.valueOf(id),
                    rs.getString("reservation_no"),
                    String.valueOf(storeId),
                    rs.getString("store_name"),
                    start,
                    rs.getInt("party_size"),
                    status,
                    rs.getString("cancel_reason"),
                    instant(rs.getObject("requested_at", LocalDateTime.class)),
                    deposit,
                    (Integer) rs.getObject("refund_amount"),
                    visitAmount,
                    couponDiscount,
                    visitAmount == null ? null : Math.max(0, visitAmount - couponDiscount),
                    instant(rs.getObject("visited_at", LocalDateTime.class)),
                    root == null ? null : String.valueOf(((Number) root).longValue()),
                    couponApply.appliedCouponFor(id),
                    cancelable && refundIfCanceled != null,
                    refundIfCanceled);
        }, customerId);

        // 묶음: 기준 예약 id(다음 코스면 course_root_id, 아니면 자기 id). 다음 코스의 기준 예약이 남의 것일 수는 없다(D12)
        Map<String, List<MyReservation>> groups = new LinkedHashMap<>();
        for (MyReservation r : rows) {
            String key = r.courseRootId() != null ? r.courseRootId() : r.id();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> courses = groups.entrySet().stream()
                .map(e -> {
                    List<MyReservation> list = e.getValue().stream()
                            .sorted(Comparator.comparing(MyReservation::startAt)).toList();
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("courseRootId", list.size() > 1 || list.get(0).courseRootId() != null ? e.getKey() : null);
                    g.put("reservations", list);
                    return g;
                })
                .sorted(Comparator.comparing(g -> ((List<MyReservation>) g.get("reservations")).get(0).startAt()))
                .toList();
        return Map.of("items", courses);
    }

    /**
     * 내 예약 한 건. 보낸 가게 · 수수료는 손님에게 보여주지 않는다(FR-CUS-05, 11-25 (5)).
     *
     * @param cancelable 지금 취소할 수 있는지(신청 · 확정이고 가게 취소 마감 전)
     * @param refundIfCanceled 지금 취소하면 돌려받는 예약금(취소할 수 없으면 null, D6 규칙)
     */
    record MyReservation(
            String id,
            String reservationNo,
            String storeId,
            String storeName,
            Instant startAt,
            int partySize,
            String status,
            String cancelReason,
            Instant requestedAt,
            int depositAmount,
            Integer refundAmount,
            Integer visitAmount,
            Integer couponDiscount,
            Integer amountAfterDiscount,
            Instant visitedAt,
            String courseRootId,
            AppliedCoupon coupon,
            boolean cancelable,
            Integer refundIfCanceled) {
    }

}
