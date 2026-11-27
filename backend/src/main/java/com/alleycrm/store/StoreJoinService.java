package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.service.CouponStatusService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가게 가입 신청 · 재신청 · 상인회 승인 · 거절(FR-STO-01, FR-AUTH-04), 탈퇴 신청 · 처리(FR-STO-03). */
@Service
public class StoreJoinService {

    private final StoreRepository stores;
    private final AccountService accountService;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final CouponStatusService coupons;
    private final Clock clock;

    StoreJoinService(StoreRepository stores, AccountService accountService, JdbcTemplate jdbc,
            ApplicationEventPublisher events, CouponStatusService coupons, Clock clock) {
        this.stores = stores;
        this.accountService = accountService;
        this.jdbc = jdbc;
        this.events = events;
        this.coupons = coupons;
        this.clock = clock;
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

    /** 사장님: 탈퇴 신청. 승인된 가게만, 미정산이 있으면 409. 상인회가 처리하기 전까지 가게는 그대로 운영된다(FR-STO-03 #3). */
    @Transactional
    public Store requestWithdrawal(AccountPrincipal me) {
        Store store = mine(me);
        if (!StoreJoinStatus.APPROVED.equals(store.getJoinStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "승인된 가게만 탈퇴를 신청할 수 있습니다.");
        }
        requireSettled(store);
        store.decide(StoreJoinStatus.WITHDRAW_REQUESTED);
        return store;
    }

    /**
     * 상인회: 자기 권역의 탈퇴 신청을 처리한다. 미정산을 다시 확인하고(FR-STO-03 #1), 같은 트랜잭션에서
     * 그 가게 미사용 쿠폰을 적용 취소한다(FR-CPN-07 #4). 다른 권역 가게는 404.
     */
    @Transactional
    public Store withdraw(AccountPrincipal me, long storeId) {
        me.requireRole(AccountRole.ASSOCIATION);
        Store store = stores.findById(storeId)
                .filter(s -> s.getRegionId().equals(me.regionId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!StoreJoinStatus.WITHDRAW_REQUESTED.equals(store.getJoinStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "탈퇴 신청 중인 가게만 탈퇴 처리할 수 있습니다.");
        }
        requireSettled(store);
        store.withdraw(Instant.now(clock));
        coupons.cancelUnusedForStore(store.getId());
        return store;
    }

    /** 미정산 = 그 가게가 낸 소개 수수료가 있는 달 중 상인회가 확정하지 않은 달(05-policy 「정산」, 11-24) */
    private void requireSettled(Store store) {
        Boolean unsettled = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM referral_fee f
                               WHERE f.receiving_store_id = ?
                                 AND NOT EXISTS (SELECT 1 FROM settlement s
                                                 WHERE s.region_id = ? AND s.month = f.settlement_month AND s.status = 'confirmed'))
                """, Boolean.class, store.getId(), store.getRegionId());
        if (Boolean.TRUE.equals(unsettled)) {
            throw new ApiException(ErrorCode.CONFLICT, "정산이 확정되지 않은 소개 수수료가 있습니다.");
        }
    }
}
