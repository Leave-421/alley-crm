package com.alleycrm.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** 역할 · 가게 접근 검사 (FR-AUTH-01). */
class AccountPrincipalTest {

    private final AccountPrincipal customer = new AccountPrincipal(1, "cust", AccountRole.CUSTOMER, null, null);
    private final AccountPrincipal ownerOfStoreA = new AccountPrincipal(2, "ownera", AccountRole.OWNER, 10L, null);
    private final AccountPrincipal association = new AccountPrincipal(3, "assoc", AccountRole.ASSOCIATION, null, 1L);

    @Test
    void 손님이_사장님_상인회_기능을_요청하면_403_forbidden_role() {
        // FR-AUTH-01 #1
        assertThatThrownBy(() -> customer.requireRole(AccountRole.OWNER)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
        assertThatThrownBy(() -> customer.requireRole(AccountRole.ASSOCIATION))
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
        assertThatThrownBy(() -> customer.requireOwnStore(10L))
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
    }

    @Test
    void 사장님_A가_가게_B를_요청하면_404_not_found() {
        // FR-AUTH-01 #2
        assertThatThrownBy(() -> ownerOfStoreA.requireOwnStore(11L))
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatCode(() -> ownerOfStoreA.requireOwnStore(10L)).doesNotThrowAnyException();
    }

    @Test
    void 상인회는_가게_데이터_경로에서_403() {
        assertThatThrownBy(() -> association.requireOwnStore(10L))
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
        assertThatCode(() -> association.requireRole(AccountRole.ASSOCIATION)).doesNotThrowAnyException();
    }
}
