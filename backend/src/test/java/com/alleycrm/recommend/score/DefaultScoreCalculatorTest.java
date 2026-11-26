package com.alleycrm.recommend.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Base;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Candidate;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Moment;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Score;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 공공데이터 기본값 점수(E5, 05-policy 「다음 방문 추천」 2단계, FR-CRS-04, 결정 11-26). DB 없이 확인한다. */
class DefaultScoreCalculatorTest {

    private static final Base CAFE_AT_ORIGIN = new Base("cafe", 0.0, 0.0);
    private static final Moment FRIDAY_AFTER_AFTERNOON = new Moment(DayOfWeek.FRIDAY, TimeBand.EVENING);

    /** 기본 가중치 0.25 · 0.25 · 0.25 · 0.125 · 0.125(05-policy 「추천 설정값」). */
    private static final Map<DefaultScoreTerm, Double> DEFAULT_WEIGHTS = new EnumMap<>(Map.of(
            DefaultScoreTerm.ATTRACTIVENESS, 0.25, DefaultScoreTerm.DISTANCE, 0.25, DefaultScoreTerm.CLQ, 0.25,
            DefaultScoreTerm.TIME_BAND, 0.125, DefaultScoreTerm.DEMOGRAPHIC, 0.125));

    /**
     * 디저트 · 주점 · 공방(매출 행 없음). 디저트는 낮 · 여성 · 20대, 주점은 금요일 저녁 · 남성 · 30대가 많다.
     */
    private static PublicStats stats() {
        return new PublicStats(
                Map.of("dessert", 0.2, "pub", 0.8),
                Map.of("cafe", Map.of("dessert", 1.5, "pub", 0.5, "workshop", 1.0)),
                Map.of("dessert", days(0.1), "pub", Map.of(DayOfWeek.FRIDAY, 0.4, DayOfWeek.MONDAY, 0.1)),
                Map.of("dessert", Map.of(TimeBand.EVENING, 0.2, TimeBand.AFTERNOON, 0.6),
                        "pub", Map.of(TimeBand.EVENING, 0.5, TimeBand.AFTERNOON, 0.05)),
                Map.of("dessert", Map.of("female", 0.7, "male", 0.3), "pub", Map.of("female", 0.4, "male", 0.6)),
                Map.of("dessert", Map.of("age_20_or_under", 0.6, "age_30s", 0.2),
                        "pub", Map.of("age_20_or_under", 0.3, "age_30s", 0.4)));
    }

    private static Map<DayOfWeek, Double> days(double share) {
        Map<DayOfWeek, Double> m = new HashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            m.put(d, share);
        }
        return m;
    }

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate(1L, "dessert", 10.0, 0.0),
            new Candidate(2L, "pub", 100.0, 0.0),
            new Candidate(3L, "workshop", 55.0, 0.0));

    @Test
    void 손님_성별_연령대를_모르면_손님층_항을_빼고_가중치를_합_1로_다시_맞춘다_FR_CRS_04_3() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(scores).allSatisfy(s -> assertThat(s.terms()).doesNotContainKey(DefaultScoreTerm.DEMOGRAPHIC));
        Map<DefaultScoreTerm, Double> used = DefaultScoreCalculator.usedWeights(DEFAULT_WEIGHTS, Profile.UNKNOWN);
        assertThat(used.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(used.get(DefaultScoreTerm.TIME_BAND)).isCloseTo(0.125 / 0.875, within(1e-9));
        for (Score s : scores) {
            double expected = s.terms().entrySet().stream()
                    .mapToDouble(e -> used.get(e.getKey()) * e.getValue()).sum();
            assertThat(s.s0()).isCloseTo(expected, within(1e-9));
        }
    }

    @Test
    void 손님_정보를_알면_다섯_항을_모두_쓴다() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                new Profile("male", "age_30s"), stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(scores).allSatisfy(s -> assertThat(s.terms()).containsOnlyKeys(DefaultScoreTerm.values()));
        // 남성 · 30대: 주점(0.6 × 0.4) > 디저트(0.3 × 0.2)
        assertThat(term(scores, 2L, DefaultScoreTerm.DEMOGRAPHIC)).isEqualTo(1.0);
        assertThat(term(scores, 1L, DefaultScoreTerm.DEMOGRAPHIC)).isEqualTo(0.0);
    }

    @Test
    void 성별만_알면_성별_비중만_쓴다() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                new Profile("female", null), stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(term(scores, 1L, DefaultScoreTerm.DEMOGRAPHIC)).isEqualTo(1.0); // 디저트 여성 0.7 > 주점 0.4
        assertThat(term(scores, 2L, DefaultScoreTerm.DEMOGRAPHIC)).isEqualTo(0.0);
    }

    @Test
    void 매출_행이_없는_업종은_매출_항을_나머지_후보_평균으로_채운다_결정_11_26() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                new Profile("male", "age_30s"), stats(), DEFAULT_WEIGHTS, 1.0);

        // 디저트 0, 주점 1 → 공방은 평균 0.5
        assertThat(term(scores, 3L, DefaultScoreTerm.ATTRACTIVENESS)).isCloseTo(0.5, within(1e-9));
        assertThat(term(scores, 3L, DefaultScoreTerm.TIME_BAND)).isCloseTo(0.5, within(1e-9));
        assertThat(term(scores, 3L, DefaultScoreTerm.DEMOGRAPHIC)).isCloseTo(0.5, within(1e-9));
        // CLQ는 근사 분류로 값이 있다(카페 → 공방 1.0): (1.0 − 0.5) ÷ (1.5 − 0.5)
        assertThat(term(scores, 3L, DefaultScoreTerm.CLQ)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void 가까울수록_거리_항이_높다() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(term(scores, 1L, DefaultScoreTerm.DISTANCE)).isEqualTo(1.0);
        assertThat(term(scores, 2L, DefaultScoreTerm.DISTANCE)).isEqualTo(0.0);
        assertThat(term(scores, 3L, DefaultScoreTerm.DISTANCE)).isBetween(0.0, 1.0);
    }

    @Test
    void 시간대_항은_기준_요일과_다음_시간대_비중을_곱한다() {
        List<Score> friday = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);
        // 금요일 · 저녁: 주점 0.4 × 0.5 = 0.2 > 디저트 0.1 × 0.2 = 0.02
        assertThat(term(friday, 2L, DefaultScoreTerm.TIME_BAND)).isEqualTo(1.0);

        List<Score> mondayAfternoon = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES,
                new Moment(DayOfWeek.MONDAY, TimeBand.AFTERNOON), Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);
        // 월요일 · 오후: 디저트 0.1 × 0.6 = 0.06 > 주점 0.1 × 0.05
        assertThat(term(mondayAfternoon, 1L, DefaultScoreTerm.TIME_BAND)).isEqualTo(1.0);
    }

    @Test
    void lift0은_후보_평균_대비_몇_배다() {
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(scores.stream().mapToDouble(Score::lift0).average().orElseThrow()).isCloseTo(1.0, within(1e-9));
        assertThat(scores).extracting(Score::storeId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 모두_같거나_좌표가_없으면_중립값() {
        List<Candidate> sameCategory = List.of(new Candidate(1L, "pub", null, null), new Candidate(2L, "pub", 5.0, 5.0));
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, sameCategory, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0);

        assertThat(scores).allSatisfy(s -> {
            assertThat(s.terms().get(DefaultScoreTerm.ATTRACTIVENESS)).isEqualTo(0.5);
            assertThat(s.terms().get(DefaultScoreTerm.DISTANCE)).isEqualTo(0.5); // 값이 하나뿐 → 모두 같음
        });
        assertThat(DefaultScoreCalculator.score(CAFE_AT_ORIGIN, List.of(), FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), DEFAULT_WEIGHTS, 1.0)).isEmpty();
    }

    @Test
    void 가중치가_0인_항은_빼고_합이_0이면_계산하지_않는다() {
        Map<DefaultScoreTerm, Double> distanceOnly = new EnumMap<>(DefaultScoreTerm.class);
        distanceOnly.put(DefaultScoreTerm.DISTANCE, 1.0);
        List<Score> scores = DefaultScoreCalculator.score(CAFE_AT_ORIGIN, CANDIDATES, FRIDAY_AFTER_AFTERNOON,
                Profile.UNKNOWN, stats(), distanceOnly, 1.0);
        assertThat(scores).allSatisfy(s -> assertThat(s.terms()).containsOnlyKeys(DefaultScoreTerm.DISTANCE));

        assertThatThrownBy(() -> DefaultScoreCalculator.usedWeights(Map.of(), Profile.UNKNOWN))
                .isInstanceOf(IllegalStateException.class);
    }

    private static double term(List<Score> scores, long storeId, DefaultScoreTerm term) {
        return scores.stream().filter(s -> s.storeId() == storeId).findFirst().orElseThrow().terms().get(term);
    }
}
