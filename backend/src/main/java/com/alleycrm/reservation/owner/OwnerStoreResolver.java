package com.alleycrm.reservation.owner;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 로그인한 사장님의 가게 id. 가게는 경로 · 요청으로 받지 않고 계정으로만 정한다(IDOR).
 * C2 로그인(인증 이름 = account.login_id)을 전제로 한다. C2가 공통 도구를 만들면 그것으로 바꾼다.
 */
@Component
public class OwnerStoreResolver {

    private final JdbcTemplate jdbc;

    OwnerStoreResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long storeIdOf(Authentication auth) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, store_id FROM account WHERE login_id = ?", auth.getName());
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Map<String, Object> a = rows.get(0);
        if (!"owner".equals(a.get("role")) || a.get("store_id") == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return ((Number) a.get("store_id")).longValue();
    }
}
