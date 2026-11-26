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
        boolean fromRecommendation) {

    /**
     * 적용된 쿠폰(CONTRACTS_API 3.5 · 예약 응답 {@code coupon}). 혜택 칸의 뜻은 3.2 CouponEvent와 같다.
     * 정률 할인은 신청 때 할인액을 정할 수 없어 {@code discountAmount}가 null이고, 실제 할인액은 방문 처리 때 정한다(E14).
     */
    public record AppliedCoupon(
            String couponId,
            String benefitType,
            Integer discountAmount,
            Integer discountPercent,
            Integer maxDiscountAmount,
            String serviceName) {
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
                r.getCourseProposalId() != null);
    }
}
