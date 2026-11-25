package com.alleycrm.account;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 로그인한 계정. 세션에 저장되고, Controller는 {@code @AuthenticationPrincipal AccountPrincipal me}로 받는다.
 * {@code Authentication.getName()}은 loginId다. 비밀번호는 담지 않는다.
 */
public record AccountPrincipal(long id, String loginId, String role, Long storeId, Long regionId)
        implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return loginId;
    }

    /** 역할이 다르면 403 forbidden_role. */
    public void requireRole(String required) {
        if (!role.equals(required)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
    }

    /** 사장님 본인 가게만 통과. 다른 가게면 존재를 숨기고 404 not_found(IDOR 방어). */
    public void requireOwnStore(long storeIdInPath) {
        requireRole(AccountRole.OWNER);
        if (storeId == null || storeId != storeIdInPath) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
