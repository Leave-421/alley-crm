package com.alleycrm.account;

import com.alleycrm.reservation.ReservationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 비회원 예약 조회(G-13). 계약: CONTRACTS_API 「비회원 예약 조회 (C13)」. */
@RestController
class GuestController {

    private final GuestSessionService guests;

    GuestController(GuestSessionService guests) {
        this.guests = guests;
    }

    @PostMapping("/api/v1/guest/lookup")
    ReservationResponse lookup(@Valid @RequestBody GuestLookupRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        ReservationResponse reservation = guests.lookup(body.reservationNo().strip(), body.phone());
        SessionStarter.start(new GuestPrincipal(body.phone()), GuestPrincipal.ROLE, request, response);
        return reservation;
    }

    @GetMapping("/api/v1/guest/reservations")
    Map<String, List<ReservationResponse>> reservations(@AuthenticationPrincipal GuestPrincipal guest) {
        return Map.of("items", guests.reservations(guest));
    }
}
