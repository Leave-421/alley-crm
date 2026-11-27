package com.alleycrm.reservation;

import com.alleycrm.account.GuestPrincipal;
import com.alleycrm.account.GuestSessionService;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 예약 취소 · 변경(G-13, D6 · D8). 비회원 예약 조회 제한 세션(C13)에서만 쓴다.
 * 규칙 · 응답은 회원과 같다({@link CustomerReservationController}). 그 전화번호의 비회원 예약이 아니면 404.
 */
@RestController
class GuestReservationController {

    private final ReservationStateService states;
    private final GuestSessionService guests;
    private final ReservationRepository reservations;

    GuestReservationController(ReservationStateService states, GuestSessionService guests, ReservationRepository reservations) {
        this.states = states;
        this.guests = guests;
        this.reservations = reservations;
    }

    @PostMapping("/api/v1/guest/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable long id, @AuthenticationPrincipal GuestPrincipal guest) {
        return ReservationResponse.of(states.cancelByCustomer(customerOf(guest, id), id), null);
    }

    @PostMapping("/api/v1/guest/reservations/{id}/change")
    ReservationResponse change(@PathVariable long id, @RequestBody ChangeRequest body, @AuthenticationPrincipal GuestPrincipal guest) {
        if (body == null || (body.startAt() == null && body.partySize() == null)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return ReservationResponse.of(states.requestChange(customerOf(guest, id), id, body.startAt(), body.partySize()), null);
    }

    /** 제한 세션 전화번호의 비회원 예약이면 그 예약의 손님 id. 아니면 존재를 숨기고 404 */
    private long customerOf(GuestPrincipal guest, long reservationId) {
        if (guest == null || !guests.reservationIds(guest).contains(reservationId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return reservations.findById(reservationId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).getCustomerId();
    }

    record ChangeRequest(Instant startAt, Integer partySize) {
    }
}
