package com.alleycrm.recommend.score;

import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 실제 기록 점수 · 섞기 · 성별 · 연령대 집단(E6, 05-policy 「다음 방문 추천」 3 ~ 5단계, FR-CRS-03 · 04). DB 없이 계산만 한다.
 *
 * <pre>
 * n_A   = A를 방문한 고유 손님 수
 * n_AB  = A 방문 고유 손님 중 같은 날 A 바로 다음으로 B를 방문한 고유 손님 수
 * lift1 = (n_AB ÷ n_A) ÷ share(B),  share(B) = B 방문 고유 손님 수 ÷ 권역 방문 고유 손님 수
 * L     = n_AB ≥ 최소 손님 수 ? (n_A × lift1 + k × lift0) ÷ (n_A + k) : lift0
 * 최종  = 집단 n_AB ≥ 최소 손님 수 ? (1 − g) × L + g × L_g : L
 * </pre>
 * 집단은 손님과 성별 · 연령대가 같은 손님(아는 쪽만으로 묶음)이고, 집단 계산의 기본값(lift0)은 2단계와 같다.
 */
public final class RecordScoreCalculator {

    /** 방문 처리된 예약 한 건. {@code day}는 운영 시간대 날짜, {@code order}는 같은 날 순서를 정하는 값(시작 시각). */
    public record Visit(long customerId, long storeId, LocalDate day, long order, String gender, String ageGroup) {
    }

    /** 실제 기록 쪽 설정값(NFR-04): k · 최소 고유 손님 수 · 집단 비율 g. */
    public record Params(double smoothingK, int minUniqueCustomers, double groupRatio) {
    }

    /**
     * 후보 한 곳의 최종 점수. {@code lift1}은 실제 기록을 쓰지 않으면 null. {@code nA} · {@code nAB}는 배지 판단용(응답에 넣지 않는다).
     *
     * @param visitDataBased '방문 데이터 기반' 배지 조건: n_AB ≥ 최소 손님 수이고 n_A ≥ k(실제 기록 비중이 절반 이상, 05-policy 6단계)
     */
    public record Result(long storeId, double finalScore, double blended, double lift0, Double lift1, int nA, int nAB,
            boolean groupUsed, boolean visitDataBased) {
    }

    private RecordScoreCalculator() {
    }

    public static List<Result> score(long baseStoreId, Map<Long, Double> lift0ByStore, List<Visit> visits,
            Profile profile, Params params) {
        Flows all = Flows.of(visits, v -> true);
        Flows group = profile.isKnown() ? Flows.of(visits, sameGroup(profile)) : null;
        List<Result> results = new ArrayList<>();
        for (Map.Entry<Long, Double> e : lift0ByStore.entrySet()) {
            long b = e.getKey();
            double lift0 = e.getValue();
            Blend overall = blend(all, baseStoreId, b, lift0, params);
            double finalScore = overall.value();
            boolean groupUsed = false;
            if (group != null) {
                Blend g = blend(group, baseStoreId, b, lift0, params);
                if (g.recordUsed()) {
                    finalScore = (1 - params.groupRatio()) * overall.value() + params.groupRatio() * g.value();
                    groupUsed = true;
                }
            }
            boolean visitData = overall.nAB() >= params.minUniqueCustomers() && overall.nA() >= params.smoothingK();
            results.add(new Result(b, finalScore, overall.value(), lift0, overall.lift1(), overall.nA(), overall.nAB(),
                    groupUsed, visitData));
        }
        return results;
    }

    private record Blend(double value, Double lift1, int nA, int nAB, boolean recordUsed) {
    }

    private static Blend blend(Flows flows, long a, long b, double lift0, Params params) {
        int nA = flows.visitors(a);
        int nAB = flows.transitions(a, b);
        int nB = flows.visitors(b);
        if (nAB < params.minUniqueCustomers() || nA == 0 || nB == 0 || flows.total() == 0) {
            return new Blend(lift0, null, nA, nAB, false);
        }
        double share = (double) nB / flows.total();
        double lift1 = ((double) nAB / nA) / share;
        double value = (nA * lift1 + params.smoothingK() * lift0) / (nA + params.smoothingK());
        return new Blend(value, lift1, nA, nAB, true);
    }

    private static Predicate<Visit> sameGroup(Profile profile) {
        return v -> (profile.gender() == null || profile.gender().equals(v.gender()))
                && (profile.ageGroup() == null || profile.ageGroup().equals(v.ageGroup()));
    }

    /** 고유 손님 단위 방문 · 같은 날 바로 다음 이동. */
    private static final class Flows {
        private final Map<Long, Set<Long>> visitorsByStore = new HashMap<>();
        private final Map<Long, Map<Long, Set<Long>>> transitionCustomers = new HashMap<>();
        private final Set<Long> allCustomers = new HashSet<>();

        static Flows of(List<Visit> visits, Predicate<Visit> include) {
            Flows f = new Flows();
            Map<String, List<Visit>> byCustomerDay = visits.stream().filter(include)
                    .collect(Collectors.groupingBy(v -> v.customerId() + "@" + v.day()));
            for (List<Visit> day : byCustomerDay.values()) {
                List<Visit> ordered = day.stream().sorted(Comparator.comparingLong(Visit::order)).toList();
                for (int i = 0; i < ordered.size(); i++) {
                    Visit v = ordered.get(i);
                    f.allCustomers.add(v.customerId());
                    f.visitorsByStore.computeIfAbsent(v.storeId(), k -> new HashSet<>()).add(v.customerId());
                    if (i + 1 < ordered.size() && !Objects.equals(ordered.get(i + 1).storeId(), v.storeId())) {
                        f.transitionCustomers.computeIfAbsent(v.storeId(), k -> new HashMap<>())
                                .computeIfAbsent(ordered.get(i + 1).storeId(), k -> new HashSet<>())
                                .add(v.customerId());
                    }
                }
            }
            return f;
        }

        int visitors(long store) {
            return visitorsByStore.getOrDefault(store, Set.of()).size();
        }

        int transitions(long from, long to) {
            return transitionCustomers.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).size();
        }

        int total() {
            return allCustomers.size();
        }
    }
}
