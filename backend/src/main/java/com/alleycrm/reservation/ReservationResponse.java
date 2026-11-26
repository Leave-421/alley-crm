package com.alleycrm.reservation;

import java.time.Instant;

/**
 * 계약 CONTRACTS_API 「예약 응답 Reservation」. 손님 이름 · 전화번호와 보낸 가게는 넣지 않는다(FR-CUS-05).
 */
public record ReservationResponse(
        String id,
        String reservationNo,
        String storeId,
        Instant startAt,
        int partySize,
        String status,
        String cancelReason,
        Instant requestedAt,
        String requestNote,
        int depositAmount,
        AppliedCoupon coupon,
        String courseRootId,
        boolean fromRecommendation,
        Integer visitAmount,
        Instant visitedAt) {

    public record AppliedCoupon(String couponId, int discountAmount) {
    }

    public static ReservationResponse of(Reservation r, AppliedCoupon coupon) {
        return new ReservationResponse(
                String.valueOf(r.getId()),
                r.getReservationNo(),
                String.valueOf(r.getStoreId()),
                r.getStartAt(),
                r.getPartySize(),
                r.getStatus(),
                r.getCancelReason(),
                r.getRequestedAt(),
                r.getRequestNote(),
                r.getDepositAmount(),
                coupon,
                r.getCourseRootId() == null ? null : String.valueOf(r.getCourseRootId()),
                r.getCourseProposalId() != null,
                r.getVisitAmount(),
                r.getVisitedAt());
    }
}
