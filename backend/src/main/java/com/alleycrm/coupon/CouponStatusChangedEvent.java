package com.alleycrm.coupon;

/**
 * 쿠폰이 '사용 완료'(사장님 사용 확인) 또는 '기간 만료'(만료 작업)가 됨(E14, CONTRACTS_API 3.6 「쿠폰 상태 알림」).
 * 상태를 바꾼 트랜잭션 안에서 내보낸다(커밋 전). C14가 커밋 뒤 리스너로 받아 그 회원에게 {@code coupon_status} 알림을 만든다.
 *
 * @param status {@code used} · {@code expired} ({@code CONTRACTS.md} 「3」 {@code coupon.status})
 */
public record CouponStatusChangedEvent(long couponId, long customerId, String status) {
}
