package com.alleycrm.recommend.score;

import com.alleycrm.recommend.domain.TimeBand;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 공공데이터 기본값 점수(E5, 05-policy 「다음 방문 추천」 2단계, FR-CRS-04). DB 없이 계산만 한다.
 *
 * <ol>
 *   <li>항마다 후보의 원래 값을 구한다: 매력도 · 거리(Huff 거리 감쇠 {@code 1 / d^β}) · CLQ · 시간대 · 손님층</li>
 *   <li>항마다 후보들 사이에서 0 ~ 1로 맞춘다(최소 0, 최대 1). 모두 같으면 0.5</li>
 *   <li>값이 없는 업종(공방 · 소품샵 매출 항 등)은 나머지 후보의 평균으로 채운다 — 중립값(결정 11-26 P-4)</li>
 *   <li>가중치를 곱해 더한다({@code s0}). 손님의 성별 · 연령대를 모르면 손님층 항을 빼고 나머지 가중치를 합 1로 맞춘다(FR-CRS-04 #3)</li>
 *   <li>{@code lift0 = s0 ÷ 후보 전체 s0 평균}(후보를 고르게 뽑았을 때 대비 몇 배)</li>
 * </ol>
 * 시간대 · 손님층의 교차값은 비중을 곱해 추정한다(독립 가정, 추정값 — 결정 11-26 P-1).
 */
public final class DefaultScoreCalculator {

    /** 같은 자리 가게끼리 거리가 0이 되지 않도록 둔 최소 거리(지도 좌표 단위, 미터). */
    static final double MIN_DISTANCE = 1.0;
    static final double NEUTRAL_WHEN_EQUAL = 0.5;

    /** 기준 가게 A. 좌표는 권역 지도 평면(06-data STORE map_x · map_y). 좌표가 없으면 null. */
    public record Base(String category, Double x, Double y) {
    }

    public record Candidate(long storeId, String category, Double x, Double y) {
    }

    /** 시간대 항의 기준: 기준 시각의 요일, 그 시각 다음 시간대(05-policy). */
    public record Moment(DayOfWeek day, TimeBand nextBand) {
    }

    /** 손님의 성별 · 연령대(CONTRACTS.md 「3」 값). 모르면 null. */
    public record Profile(String gender, String ageGroup) {

        public static final Profile UNKNOWN = new Profile(null, null);

        boolean isKnown() {
            return gender != null || ageGroup != null;
        }
    }

    /**
     * 후보 한 곳의 기본값 점수. {@code terms}는 0 ~ 1로 맞춘 항별 값(쓴 항만) — E7이 이유 문구를 고를 때 쓴다.
     */
    public record Score(long storeId, double s0, double lift0, Map<DefaultScoreTerm, Double> terms) {
    }

    private DefaultScoreCalculator() {
    }

    public static List<Score> score(Base base, List<Candidate> candidates, Moment moment, Profile profile,
            PublicStats stats, Map<DefaultScoreTerm, Double> weights, double distanceExponent) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<DefaultScoreTerm, Double> used = usedWeights(weights, profile);
        Map<DefaultScoreTerm, double[]> normalized = new EnumMap<>(DefaultScoreTerm.class);
        for (DefaultScoreTerm term : used.keySet()) {
            Function<Candidate, Optional<Double>> raw = switch (term) {
                case ATTRACTIVENESS -> c -> stats.attractivenessOf(c.category());
                case DISTANCE -> c -> distance(base, c).map(d -> 1.0 / Math.pow(Math.max(MIN_DISTANCE, d), distanceExponent));
                case CLQ -> c -> stats.clqOf(base.category(), c.category());
                case TIME_BAND -> c -> stats.timeOf(c.category(), moment.day(), moment.nextBand());
                case DEMOGRAPHIC -> c -> stats.demographicOf(c.category(), profile.gender(), profile.ageGroup());
            };
            normalized.put(term, normalize(candidates.stream().map(raw).toList()));
        }

        double[] s0 = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            for (Map.Entry<DefaultScoreTerm, Double> w : used.entrySet()) {
                s0[i] += w.getValue() * normalized.get(w.getKey())[i];
            }
        }
        double mean = Arrays.stream(s0).average().orElse(0);
        List<Score> scores = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<DefaultScoreTerm, Double> terms = new EnumMap<>(DefaultScoreTerm.class);
            for (DefaultScoreTerm term : used.keySet()) {
                terms.put(term, normalized.get(term)[i]);
            }
            scores.add(new Score(candidates.get(i).storeId(), s0[i], mean > 0 ? s0[i] / mean : 1.0, Map.copyOf(terms)));
        }
        return scores;
    }

    /** 지도 좌표 사이 거리. 어느 한쪽 좌표가 없으면 빈 값(그 후보는 거리 항 중립값). */
    private static Optional<Double> distance(Base base, Candidate c) {
        if (base.x() == null || base.y() == null || c.x() == null || c.y() == null) {
            return Optional.empty();
        }
        return Optional.of(Math.hypot(c.x() - base.x(), c.y() - base.y()));
    }

    /** 쓸 항과 합이 1이 되게 맞춘 가중치. 손님 정보를 모르면 손님층 항을 뺀다. 가중치가 0인 항은 계산하지 않는다. */
    static Map<DefaultScoreTerm, Double> usedWeights(Map<DefaultScoreTerm, Double> weights, Profile profile) {
        Map<DefaultScoreTerm, Double> used = new EnumMap<>(DefaultScoreTerm.class);
        for (DefaultScoreTerm term : DefaultScoreTerm.values()) {
            double w = weights.getOrDefault(term, 0.0);
            if (w > 0 && (term != DefaultScoreTerm.DEMOGRAPHIC || profile.isKnown())) {
                used.put(term, w);
            }
        }
        double total = used.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            throw new IllegalStateException("추천 가중치 합이 0입니다.");
        }
        used.replaceAll((term, w) -> w / total);
        return used;
    }

    /** 0 ~ 1로 맞춘다. 값이 없는 후보는 나머지 후보 평균(중립값). 모두 같거나 값이 하나도 없으면 0.5. */
    static double[] normalize(List<Optional<Double>> raw) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Optional<Double> v : raw) {
            if (v.isPresent()) {
                min = Math.min(min, v.get());
                max = Math.max(max, v.get());
            }
        }
        double[] out = new double[raw.size()];
        if (min == Double.POSITIVE_INFINITY || max - min < 1e-12) {
            Arrays.fill(out, NEUTRAL_WHEN_EQUAL);
            return out;
        }
        double sum = 0;
        int present = 0;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).isPresent()) {
                out[i] = (raw.get(i).get() - min) / (max - min);
                sum += out[i];
                present++;
            }
        }
        double neutral = sum / present;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).isEmpty()) {
                out[i] = neutral;
            }
        }
        return out;
    }
}
