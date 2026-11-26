package com.alleycrm.recommend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.score.DefaultScoreTerm;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 제안 이유(05-policy 「다음 방문 추천」 6단계, FR-CRS-05). */
class RecommendationReasonTest {

    private static final List<String> CATEGORIES = List.of(
            "restaurant", "cafe", "dessert", "pub", "workshop", "bookstore", "gift_shop", "stationery");

    @Test
    void 어떤_문구에도_숫자가_없다_FR_CRS_05_1() {
        for (ReasonType type : ReasonType.values()) {
            for (String category : CATEGORIES) {
                assertThat(RecommendationReason.textOf(type, category)).isNotBlank().doesNotContainPattern("[0-9]");
            }
        }
    }

    @Test
    void 방문_데이터_기반이면_방문_데이터_근거() {
        assertThat(RecommendationReason.typeOf(true, Map.of(DefaultScoreTerm.CLQ, 1.0))).isEqualTo(ReasonType.VISIT_DATA);
    }

    @Test
    void 아니면_기본값_항_중_그_가게_값이_가장_큰_항() {
        assertThat(RecommendationReason.typeOf(false, Map.of(
                DefaultScoreTerm.CLQ, 0.2, DefaultScoreTerm.TIME_BAND, 0.9, DefaultScoreTerm.DISTANCE, 0.5)))
                .isEqualTo(ReasonType.TIME_BAND);
        assertThat(RecommendationReason.typeOf(false, Map.of(DefaultScoreTerm.DISTANCE, 1.0, DefaultScoreTerm.CLQ, 0.3)))
                .isEqualTo(ReasonType.DISTANCE);
        // 같으면 업종 동반 입지 → 시간대 → 매력도 → 손님층 → 거리 순
        assertThat(RecommendationReason.typeOf(false, Map.of(DefaultScoreTerm.DISTANCE, 0.5, DefaultScoreTerm.CLQ, 0.5)))
                .isEqualTo(ReasonType.COLOCATION);
    }

    @Test
    void 기준_가게_업종_이름을_넣는다() {
        assertThat(RecommendationReason.textOf(ReasonType.COLOCATION, "cafe")).contains("카페");
        assertThat(RecommendationReason.textOf(ReasonType.TIME_BAND, "pub")).contains("주점");
    }
}
