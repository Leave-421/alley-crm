package com.alleycrm.store;

/**
 * 사장님이 영업 상태(open · paused · closed)를 바꿈. 바꾼 트랜잭션 안에서 내보낸다(커밋 전).
 * 휴업 · 운영 중단이면 그 가게 미사용 쿠폰 적용 취소(E14, FR-CPN-07 #4)가 {@code @EventListener}로 받는다.
 */
public record StoreBusinessStatusChangedEvent(long storeId, String businessStatus) {
}
