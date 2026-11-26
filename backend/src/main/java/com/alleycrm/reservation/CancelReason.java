package com.alleycrm.reservation;

/** 취소 사유. CONTRACTS.md 「3」 `reservation.cancelReason`과 같아야 한다. */
public final class CancelReason {

    public static final String CUSTOMER_CANCELED = "customer_canceled";
    /** 사장님 거절과 예약 시각까지 승인 없는 자동 거절(FR-RSV-03) */
    public static final String STORE_REJECTED = "store_rejected";
    /** 확정 예약이 예약일 다음 날까지 방문 처리 · 취소 없음(FR-RSV-02) */
    public static final String AUTO_CANCELED = "auto_canceled";

    private CancelReason() {
    }
}
