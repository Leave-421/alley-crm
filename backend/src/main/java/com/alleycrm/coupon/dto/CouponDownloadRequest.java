package com.alleycrm.coupon.dto;

/** 쿠폰 다운로드 요청(CONTRACTS_API 3.4). 대상 가게 · 출처는 서버가 이 노출 기록에서 정한다. */
public record CouponDownloadRequest(String exposureId) {
}
