package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 회원 손님 지연 입력(D14, G-07). 비회원은 비회원 예약 조회 세션(C13)에서 같은 {@link DelayService}를 부른다. */
@RestController
class DelayController {

    private final DelayService delays;
    private final JdbcTemplate jdbc;

    DelayController(DelayService delays, JdbcTemplate jdbc) {
        this.delays = delays;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/reservations/{id}/delay")
    Map<String, Object> delay(@PathVariable long id, @RequestBody DelayRequest body, Authentication auth) {
        Instant eta = delays.reportDelay(memberCustomerId(auth), id, body == null ? null : body.delayMinutes());
        return Map.of("reservationId", String.valueOf(id), "expectedArrivalAt", eta);
    }

    record DelayRequest(Integer delayMinutes) {
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
