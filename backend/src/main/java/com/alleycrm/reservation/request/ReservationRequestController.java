package com.alleycrm.reservation.request;

import com.alleycrm.reservation.ReservationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/reservations — 예약 신청(로그인 불필요). 계약: CONTRACTS_API 「예약 신청」. */
@RestController
class ReservationRequestController {

    private final ReservationRequestService service;

    ReservationRequestController(ReservationRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResponse request(@RequestBody ReservationRequestBody body, Authentication authentication) {
        boolean loggedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return service.request(body, loggedIn ? authentication.getName() : null);
    }
}
