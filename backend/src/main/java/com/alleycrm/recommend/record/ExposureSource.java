package com.alleycrm.recommend.record;

/** 추천 노출 한 건의 출처: 어느 가게를 기준으로 어느 가게를 추천했는지(쿠폰 출처, FR-CPN-10). */
public record ExposureSource(long exposureId, long baseStoreId, long recommendedStoreId) {
}
