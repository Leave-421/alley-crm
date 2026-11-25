package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인된 사장님 확인(FR-AUTH-04). 사장님 기능의 Controller는 여기서 가게 ID를 얻는다.
 * 가입 상태는 승인 · 거절로 바뀌므로 세션 값이 아니라 매 요청 DB에서 확인한다.
 */
@Service
public class StoreService {

    private final StoreRepository stores;

    StoreService(StoreRepository stores) {
        this.stores = stores;
    }

    /** 사장님이 아니거나 가게가 승인 전 · 거절 · 탈퇴면 403 forbidden_role. 가게 ID는 세션 계정에서만 정한다. */
    @Transactional(readOnly = true)
    public long requireApprovedOwnStore(AccountPrincipal me) {
        me.requireRole(AccountRole.OWNER);
        Store store = stores.findById(me.storeId()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN_ROLE));
        if (!StoreJoinStatus.APPROVED.equals(store.getJoinStatus())) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return store.getId();
    }

    /** 경로의 가게 ID가 내 가게가 아니면 존재를 숨기고 404 not_found(IDOR), 그다음 승인 확인. */
    @Transactional(readOnly = true)
    public long requireApprovedOwnStore(AccountPrincipal me, long storeIdInPath) {
        me.requireOwnStore(storeIdInPath);
        return requireApprovedOwnStore(me);
    }
}
