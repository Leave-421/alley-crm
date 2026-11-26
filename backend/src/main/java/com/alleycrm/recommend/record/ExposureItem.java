package com.alleycrm.recommend.record;

import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.domain.RecommendationBadge;

/**
 * 지도 추천 노출로 남길 가게 하나. 순위 · 배지 · 근거 종류는 E7이 기록 시점에 다시 계산한 값이다(CONTRACTS_API 3.1).
 * 근거 종류는 없을 수 있다(표의 reason_type은 NULL 허용).
 */
public record ExposureItem(long storeId, int rank, RecommendationBadge badge, ReasonType reasonType) {
}
