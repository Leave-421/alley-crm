package com.alleycrm.coupon;

/**
 * 상인회 쿠폰 행사를 만듦(E18 · E19, CONTRACTS_API 3.9). 행사를 만든 트랜잭션 안에서 내보낸다(커밋 전).
 * C14가 커밋 뒤 리스너로 받아 회원 계정 전부에게 {@code association_coupon_opened} 알림을 만든다(FR-CPN-13).
 */
public record AssociationCouponOpenedEvent(long eventId, long regionId) {
}
