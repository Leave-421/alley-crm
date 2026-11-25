package com.alleycrm.reservation;

/** 예약 상태 값. CONTRACTS.md 「3」 `reservation.status`와 같아야 한다. */
public final class ReservationStatus {

    public static final String REQUESTED = "requested";
    public static final String CONFIRMED = "confirmed";
    public static final String VISITED = "visited";
    public static final String CANCELED = "canceled";

    private ReservationStatus() {
    }
}
