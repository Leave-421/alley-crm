package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가게 가입 신청 · 재신청 · 상인회 승인 · 거절(FR-STO-01, FR-AUTH-04). */
@Service
public class StoreJoinService {

    private final StoreRepository stores;
    private final AccountService accountService;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    StoreJoinService(StoreRepository stores, AccountService accountService, JdbcTemplate jdbc,
            ApplicationEventPublisher events) {
        this.stores = stores;
        this.accountService = accountService;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** 가게(requested)와 사장님 계정을 한 트랜잭션에서 만든다. 아이디가 겹치면 가게도 남지 않는다. */
    @Transactional
    public Store apply(long regionId, StoreJoinInfo info, String loginId, String rawPassword) {
        info.check();
        Integer regions = jdbc.queryForObject("SELECT COUNT(*) FROM region WHERE id = ?", Integer.class, regionId);
        if (regions == null || regions == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Store store = stores.saveAndFlush(Store.requested(regionId, info));
        accountService.createOwner(store.getId(), loginId, rawPassword);
        return store;
    }

    /** 사장님 본인 가게의 가입 상태(승인 전 · 거절 포함) */
    @Transactional(readOnly = true)
    public Store mine(AccountPrincipal me) {
        me.requireRole(AccountRole.OWNER);
        return stores.findById(me.storeId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 거절된 가입만 고친 정보로 다시 신청한다. 받아들일지는 상인회가 정한다. */
    @Transactional
    public Store reapply(AccountPrincipal me, StoreJoinInfo info) {
        info.check();
        Store store = mine(me);
        if (!StoreJoinStatus.REJECTED.equals(store.getJoinStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "거절된 가입만 다시 신청할 수 있습니다.");
        }
        store.reapply(info);
        return store;
    }

    /** 상인회: 자기 권역의 가입 목록, 오래된 신청부터 */
    @Transactional(readOnly = true)
    public Page<Store> list(AccountPrincipal me, String status, int page, int size) {
        me.requireRole(AccountRole.ASSOCIATION);
        return stores.findByRegionIdAndJoinStatusOrderByIdAsc(me.regionId(), status, PageRequest.of(page, size));
    }

    /** 상인회: 자기 권역의 신청 중인 가입을 승인 · 거절. 다른 권역 가게는 404. */
    @Transactional
    public Store decide(AccountPrincipal me, long storeId, boolean approve) {
        me.requireRole(AccountRole.ASSOCIATION);
        Store store = stores.findById(storeId)
                .filter(s -> s.getRegionId().equals(me.regionId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!StoreJoinStatus.REQUESTED.equals(store.getJoinStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "신청 상태인 가입만 승인 · 거절할 수 있습니다.");
        }
        store.decide(approve ? StoreJoinStatus.APPROVED : StoreJoinStatus.REJECTED);
        events.publishEvent(new StoreJoinDecidedEvent(store.getId(), store.getJoinStatus()));
        return store;
    }
}
