package com.alleycrm.reservation;

import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 회원 손님 지연 입력(D14, G-07). 비회원은 비회원 예약 조회 세션(C13)에서 같은 {@link DelayService}를 부른다. */
@RestController
class DelayController {

    private final DelayService delays;
    private final MemberCustomerResolver members;

    DelayController(DelayService delays, MemberCustomerResolver members) {
        this.delays = delays;
        this.members = members;
    }

    @PostMapping("/api/v1/reservations/{id}/delay")
    Map<String, Object> delay(@PathVariable long id, @RequestBody DelayRequest body, Authentication auth) {
        Instant eta = delays.reportDelay(members.customerId(auth), id, body == null ? null : body.delayMinutes());
        return Map.of("reservationId", String.valueOf(id), "expectedArrivalAt", eta);
    }

    record DelayRequest(Integer delayMinutes) {
    }

}
