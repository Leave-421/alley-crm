package com.alleycrm.recommend;

/**
 * 예약 신청 뒤 그 예약의 다음 가게 추천 후보가 있음(E8, CONTRACTS_API 3.11, FR-CRS-02). 예약 커밋 뒤 E의 새 트랜잭션 안에서
 * 내보낸다. C14가 커밋 뒤 리스너로 받아 그 예약의 손님(회원 계정 · 비회원 예약)에게 {@code next_store_recommended} 알림을 만든다.
 */
public record NextStoreRecommendedEvent(long reservationId, long storeId, long customerId) {
}
