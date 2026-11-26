package com.alleycrm.recommend.recommendation;

import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.score.DefaultScoreTerm;
import java.util.List;
import java.util.Map;

/**
 * 제안 이유(05-policy 「다음 방문 추천」 6단계, FR-CRS-05). 문구에 **숫자를 넣지 않는다**(손님은 흐름 · 매출 데이터를 볼 수 없음).
 * 방문 데이터 기반 배지면 방문 데이터 문구, 아니면 기본값 항 중 그 가게 값이 가장 큰 항의 문구. 최종 문구는 화면 작업에서 다듬는다.
 */
final class RecommendationReason {

    /** 값이 같을 때 고르는 순서(재현 가능). */
    private static final List<DefaultScoreTerm> TIE_ORDER = List.of(DefaultScoreTerm.CLQ, DefaultScoreTerm.TIME_BAND,
            DefaultScoreTerm.ATTRACTIVENESS, DefaultScoreTerm.DEMOGRAPHIC, DefaultScoreTerm.DISTANCE);

    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "restaurant", "음식점", "cafe", "카페", "dessert", "디저트", "pub", "주점",
            "workshop", "공방", "bookstore", "서점", "gift_shop", "소품샵", "stationery", "문구점");

    private RecommendationReason() {
    }

    static ReasonType typeOf(boolean visitDataBased, Map<DefaultScoreTerm, Double> defaultTerms) {
        if (visitDataBased) {
            return ReasonType.VISIT_DATA;
        }
        DefaultScoreTerm best = null;
        for (DefaultScoreTerm term : TIE_ORDER) {
            Double v = defaultTerms.get(term);
            if (v != null && (best == null || v > defaultTerms.get(best))) {
                best = term;
            }
        }
        if (best == null) {
            return ReasonType.ATTRACTIVENESS;
        }
        return switch (best) {
            case CLQ -> ReasonType.COLOCATION;
            case TIME_BAND -> ReasonType.TIME_BAND;
            case ATTRACTIVENESS -> ReasonType.ATTRACTIVENESS;
            case DEMOGRAPHIC -> ReasonType.DEMOGRAPHIC;
            case DISTANCE -> ReasonType.DISTANCE;
        };
    }

    /** 기준 가게 업종 이름을 넣은 문구. 숫자가 없다. */
    static String textOf(ReasonType type, String baseCategory) {
        String base = CATEGORY_LABEL.getOrDefault(baseCategory, "이 가게");
        return switch (type) {
            case VISIT_DATA -> "이 가게 손님들이 같은 날 자주 찾는 참여 가게예요";
            case COLOCATION -> "이 상권에서 " + base + " 가까이 많이 모여 있는 업종이에요";
            case TIME_BAND -> "이 시간대에 " + base + " 다음으로 많이 찾는 업종이에요";
            case ATTRACTIVENESS -> "이 지역에서 많이 찾는 업종이에요";
            case DEMOGRAPHIC -> "비슷한 연령대 손님이 많이 찾는 업종이에요";
            case DISTANCE -> base + "에서 가까워 함께 들르기 좋은 가게예요";
        };
    }
}
