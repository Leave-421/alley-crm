package com.alleycrm.reservation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 로그인한 회원 손님 계정 → customer id. 손님 계정이 아니면 `forbidden_role`. */
@Component
public class MemberCustomerResolver {

    private final JdbcTemplate jdbc;

    public MemberCustomerResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long customerId(Authentication auth) {
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
