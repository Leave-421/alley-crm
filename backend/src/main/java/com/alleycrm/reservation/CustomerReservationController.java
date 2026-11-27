package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    CustomerReservationController(ReservationStateService states, JdbcTemplate jdbc) {
        this.states = states;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable long id, Authentication auth) {
        return ReservationResponse.of(states.cancelByCustomer(memberCustomerId(auth), id), null);
    }

    /** 예약 변경(D8, FR-RSV-07): 바꿀 날짜(startAt) · 인원(partySize) 중 하나 이상. 사장님이 다시 승인한다 */
    @PostMapping("/api/v1/reservations/{id}/change")
    ReservationResponse change(@PathVariable long id, @RequestBody ChangeRequest body, Authentication auth) {
        if (body == null || (body.startAt() == null && body.partySize() == null)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return ReservationResponse.of(states.requestChange(memberCustomerId(auth), id, body.startAt(), body.partySize()), null);
    }

    record ChangeRequest(java.time.Instant startAt, Integer partySize) {
    }

    private long memberCustomerId(Authentication auth) {
        List<Long> ids = jdbc.queryForList("""
                SELECT c.id FROM account a JOIN customer c ON c.account_id = a.id
                WHERE a.login_id = ? AND a.role = 'customer'
                """, Long.class, auth.getName());
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return ids.get(0);
    }
}
