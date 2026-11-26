package com.alleycrm.recommend.score;

import com.alleycrm.recommend.domain.TimeBand;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.Optional;

/**
 * 표 public_stat의 관측값(E2, 결정 11-26 P-1). 업종 값은 계약 {@code store.category} 문자열이다.
 * 매출 행이 없는 업종(공방 · 소품샵)은 비어 있다 — 계산에서 중립값으로 채운다(P-4).
 */
public record PublicStats(
        Map<String, Double> attractiveness,
        Map<String, Map<String, Double>> clq,
        Map<String, Map<DayOfWeek, Double>> dayShare,
        Map<String, Map<TimeBand, Double>> timeBandShare,
        Map<String, Map<String, Double>> genderShare,
        Map<String, Map<String, Double>> ageShare) {

    Optional<Double> attractivenessOf(String category) {
        return Optional.ofNullable(attractiveness.get(category));
    }

    Optional<Double> clqOf(String from, String to) {
        return Optional.ofNullable(clq.getOrDefault(from, Map.of()).get(to));
    }

    /** P(요일, 시간대 | B) ≈ P(요일 | B) × P(시간대 | B). 한쪽이라도 없으면 빈 값. */
    Optional<Double> timeOf(String category, DayOfWeek day, TimeBand band) {
        Double d = dayShare.getOrDefault(category, Map.of()).get(day);
        Double t = timeBandShare.getOrDefault(category, Map.of()).get(band);
        return d == null || t == null ? Optional.empty() : Optional.of(d * t);
    }

    /** P(성별, 연령대 | B) ≈ P(성별 | B) × P(연령대 | B). 아는 쪽만 곱한다. 아는 쪽 값이 없으면 빈 값. */
    Optional<Double> demographicOf(String category, String gender, String ageGroup) {
        double value = 1.0;
        if (gender != null) {
            Double g = genderShare.getOrDefault(category, Map.of()).get(gender);
            if (g == null) {
                return Optional.empty();
            }
            value *= g;
        }
        if (ageGroup != null) {
            Double a = ageShare.getOrDefault(category, Map.of()).get(ageGroup);
            if (a == null) {
                return Optional.empty();
            }
            value *= a;
        }
        return Optional.of(value);
    }
}
