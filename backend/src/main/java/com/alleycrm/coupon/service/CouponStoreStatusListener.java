package com.alleycrm.coupon.service;

import com.alleycrm.store.StoreBusinessStatusChangedEvent;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 가게 영업 상태 변경(C22, CONTRACTS_API 3.6 「가게 운영 중단 · 탈퇴 → 적용 취소」)에 따라 쿠폰 상태를 바꾼다.
 * 휴업 · 운영 중단이면 그 가게의 '다운로드됨' · '예약에 적용됨' 쿠폰을 '적용 취소'로(FR-CPN-07 #4, FR-STO-02 #1).
 * 영업 상태를 바꾼 트랜잭션 안에서 돌아, 실패하면 영업 상태 변경도 되돌아간다. 예약 받기 '잠시 멈춤'은 이벤트가 없어 바뀌지 않는다.
 */
@Component
class CouponStoreStatusListener {

    private static final Set<String> NOT_OPERATING = Set.of("paused", "closed");

    private final CouponStatusService statusService;

    CouponStoreStatusListener(CouponStatusService statusService) {
        this.statusService = statusService;
    }

    @EventListener
    void on(StoreBusinessStatusChangedEvent event) {
        if (NOT_OPERATING.contains(event.businessStatus())) {
            statusService.cancelUnusedForStore(event.storeId());
        }
    }
}
