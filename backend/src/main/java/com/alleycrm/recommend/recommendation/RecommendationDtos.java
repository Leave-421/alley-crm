package com.alleycrm.recommend.recommendation;

import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.domain.RecommendationBadge;
import java.time.Instant;
import java.util.List;

/** 지도 추천 요청 · 응답 DTO(CONTRACTS_API 3.1). ID는 문자열, 시각은 UTC. 점수 · 손님 수 같은 숫자 근거는 넣지 않는다(FR-CRS-05). */
public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    /** RecommendationCard. */
    public record Card(int rank, CardStore store, RecommendationBadge badge, ReasonType reasonType, String reasonText,
            boolean couponBenefit) {
    }

    public record CardStore(String id, String name, String category, String alley, Double mapX, Double mapY) {
    }

    public record PublicData(String source, String period) {
    }

    /** {@code GET /api/v1/recommendations} 응답. {@code baseTimeSource}는 {@code now} · {@code reservation}. */
    public record RecommendationsResponse(String baseStoreId, Instant baseTime, String baseTimeSource, int page,
            int pageCount, List<Card> items, PublicData publicData) {
    }

    /** {@code POST /api/v1/recommendation-exposures} 요청. 값 검사는 컨트롤러. */
    public record ExposureRequest(String displayId, String baseStoreId, Integer page, List<String> storeIds) {
    }

    public record ExposureItemResponse(String storeId, String exposureId) {
    }

    public record ExposuresResponse(List<ExposureItemResponse> items) {
    }
}
