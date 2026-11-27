package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 손님 예약 취소(D6, FR-RSV-06). 손님은 로그인 계정으로 정한다.
 * 비회원 취소는 비회원 예약 조회 세션(C13)이 생기면 같은 {@link ReservationStateService#cancelByCustomer}를 부른다.
 */
@RestController
class CustomerReservationController {

    private final ReservationStateService states;
    private final MemberCustomerResolver members;

    CustomerReservationController(ReservationStateService states, MemberCustomerResolver members) {
        this.states = states;
        this.members = members;
    }

    @PostMapping("/api/v1/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable long id, Authentication auth) {
        return ReservationResponse.of(states.cancelByCustomer(members.customerId(auth), id), null);
    }

    /** 예약 변경(D8, FR-RSV-07): 바꿀 날짜(startAt) · 인원(partySize) 중 하나 이상. 사장님이 다시 승인한다 */
    @PostMapping("/api/v1/reservations/{id}/change")
    ReservationResponse change(@PathVariable long id, @RequestBody ChangeRequest body, Authentication auth) {
        if (body == null || (body.startAt() == null && body.partySize() == null)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return ReservationResponse.of(states.requestChange(members.customerId(auth), id, body.startAt(), body.partySize()), null);
    }

    record ChangeRequest(java.time.Instant startAt, Integer partySize) {
    }

}
