package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인된 사장님 확인(FR-AUTH-04). 사장님 기능의 Controller는 여기서 가게 ID를 얻는다.
 * 가입 상태는 승인 · 거절로 바뀌므로 세션 값이 아니라 매 요청 DB에서 확인한다.
 */
@Service
public class StoreService {

    /** 참여 가게: 승인됐고, 탈퇴 신청 중이어도 처리 전까지는 참여 중 */
    private static final List<String> PARTICIPATING =
            List.of(StoreJoinStatus.APPROVED, StoreJoinStatus.WITHDRAW_REQUESTED);

    private final StoreRepository stores;
    private final JdbcTemplate jdbc;

    StoreService(StoreRepository stores, JdbcTemplate jdbc) {
        this.stores = stores;
        this.jdbc = jdbc;
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

    /** 권역의 참여 가게(승인 · 탈퇴 신청), ID순. 없는 권역은 404. */
    @Transactional(readOnly = true)
    public List<Store> participatingStores(long regionId) {
        Integer regions = jdbc.queryForObject("SELECT COUNT(*) FROM region WHERE id = ?", Integer.class, regionId);
        if (regions == null || regions == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return stores.findByRegionIdAndJoinStatusInOrderByIdAsc(regionId, PARTICIPATING);
    }

    /** 상인회: 자기 권역 가게의 주소 · 골목 · 지도 좌표를 고친다(FR-STO-02). 다른 권역 가게는 404. */
    @Transactional
    public Store updateLocation(AccountPrincipal me, long storeId, String address, String alley,
            BigDecimal mapX, BigDecimal mapY) {
        me.requireRole(AccountRole.ASSOCIATION);
        Store store = stores.findById(storeId)
                .filter(s -> s.getRegionId().equals(me.regionId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        store.moveTo(address, alley, mapX, mapY);
        return store;
    }

    /** 경로의 가게 ID가 내 가게가 아니면 존재를 숨기고 404 not_found(IDOR), 그다음 승인 확인. */
    @Transactional(readOnly = true)
    public long requireApprovedOwnStore(AccountPrincipal me, long storeIdInPath) {
        me.requireOwnStore(storeIdInPath);
        return requireApprovedOwnStore(me);
    }
}
