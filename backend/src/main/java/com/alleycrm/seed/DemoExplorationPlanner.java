package com.alleycrm.seed;

import com.alleycrm.seed.DemoReservationPlanner.Customer;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 가상 데이터 2의 탐색 기록(E17 #268, 06-data 「가상 데이터 생성」 9단계): 방문자와 지도 추천 노출. DB 없이 계획만 만든다.
 * 같은 seed면 같은 결과다.
 *
 * <ul>
 *   <li>방문자: 예약한 손님마다 하나(그 손님의 첫 예약 신청 전에 연결 — 로그인 · 비회원 예약으로 이어진 것으로 본다)와
 *       예약하지 않은 익명 방문자</li>
 *   <li>노출: 가게를 눌러 본 추천 카드 묶음(한 번에 3곳), 일부는 리롤로 4 ~ 6위. 추천 가게 · 순위는 같은 날 이동 기록의
 *       순위({@link DemoReservationPlanner#nextStoreRanking}), 배지는 05-policy 기준({@code n_AB ≥ 5}이고 기준 가게 방문
 *       손님 ≥ k)</li>
 * </ul>
 * 노출은 탐색 기록이라 소개 · 수락률에 쓰지 않는다(FR-CRS-07).
 */
public final class DemoExplorationPlanner {

    /** 예약하지 않은 익명 방문자 수(손님 수에 비례). */
    static final double ANONYMOUS_VISITORS_PER_CUSTOMER = 0.4;
    /** 리롤로 다음 묶음을 본 비율. */
    static final double REROLL_RATE = 0.25;
    /** '방문 데이터 기반' 배지 기준(05-policy 6단계: n_AB ≥ 5, n_A ≥ k = 10). */
    static final int VISIT_DATA_MIN_PAIR = 5;
    static final int VISIT_DATA_MIN_BASE = 10;
    private static final String[] LOCAL_POPULAR_REASONS = {"colocation", "time_band", "attractiveness", "distance"};

    /** @param customerIndex 이어진 손님(없으면 익명) */
    public record Visitor(int index, String anonId, Integer customerIndex, Instant createdAt, Instant linkedAt) {
    }

    public record Exposure(int visitorIndex, String displayId, String baseStoreCode, String recommendedStoreCode, int rank,
            int page, String badge, String reasonType, Instant shownAt) {
    }

    public record Plan(List<Visitor> visitors, List<Exposure> exposures) {
    }

    private final List<SeedRegionData.Store> stores;
    private final DemoPeriod period;
    private final Random random;

    private DemoExplorationPlanner(List<SeedRegionData.Store> stores, DemoPeriod period, long seed) {
        this.stores = stores;
        this.period = period;
        this.random = new Random(seed);
    }

    public static Plan plan(List<SeedRegionData.Store> stores, DemoReservationPlanner.Plan reservations, long seed) {
        return new DemoExplorationPlanner(stores, reservations.period(), seed).build(reservations);
    }

    private Plan build(DemoReservationPlanner.Plan base) {
        Map<String, List<String>> ranking = DemoReservationPlanner.nextStoreRanking(base.customers(), base.reservations(),
                stores);
        Map<String, Map<String, Set<Integer>>> pairs = DemoReservationPlanner.transitionCustomers(base.reservations());
        Map<String, Long> baseVisitors = new HashMap<>();
        base.reservations().stream().filter(r -> "visited".equals(r.status()))
                .map(r -> r.storeCode() + "#" + r.customerIndex()).distinct()
                .forEach(key -> baseVisitors.merge(key.substring(0, key.indexOf('#')), 1L, Long::sum));

        Map<Integer, Instant> firstRequest = new HashMap<>();
        for (Reservation r : base.reservations()) {
            firstRequest.merge(r.customerIndex(), r.requestedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }

        List<Visitor> visitors = new ArrayList<>();
        for (Customer customer : base.customers()) {
            Instant first = firstRequest.get(customer.index());
            if (first == null) {
                continue;
            }
            Instant linkedAt = first.minus(between(5, 600), ChronoUnit.MINUTES);
            Instant createdAt = linkedAt.minus(between(0, 14 * 24 * 60), ChronoUnit.MINUTES);
            visitors.add(new Visitor(visitors.size(), uuid(), customer.index(), createdAt, linkedAt));
        }
        int anonymous = (int) Math.round(base.customers().size() * ANONYMOUS_VISITORS_PER_CUSTOMER);
        for (int i = 0; i < anonymous; i++) {
            visitors.add(new Visitor(visitors.size(), uuid(), null, randomTime(), null));
        }

        List<Exposure> exposures = new ArrayList<>();
        for (Visitor visitor : visitors) {
            int views = visitor.customerIndex() == null ? between(0, 3) : between(1, 5);
            for (int v = 0; v < views; v++) {
                Instant shownAt = later(randomTime(), visitor.createdAt());
                SeedRegionData.Store baseStore = stores.get(random.nextInt(stores.size()));
                List<String> recommended = ranking.get(baseStore.code());
                addPage(exposures, visitor, baseStore, recommended, 0, shownAt, pairs, baseVisitors);
                if (random.nextDouble() < REROLL_RATE) {
                    addPage(exposures, visitor, baseStore, recommended, 1, shownAt.plusSeconds(between(5, 60)), pairs,
                            baseVisitors);
                }
            }
        }
        exposures.sort(Comparator.comparing(Exposure::shownAt));
        return new Plan(visitors, exposures);
    }

    /** 한 묶음(3곳). 같은 표시는 같은 displayId(uk_exposure_display). */
    private void addPage(List<Exposure> exposures, Visitor visitor, SeedRegionData.Store base, List<String> recommended,
            int page, Instant shownAt, Map<String, Map<String, Set<Integer>>> pairs, Map<String, Long> baseVisitors) {
        String displayId = uuid();
        for (int i = 0; i < 3; i++) {
            int rank = page * 3 + i + 1;
            String store = recommended.get(rank - 1);
            int pair = pairs.getOrDefault(base.code(), Map.of()).getOrDefault(store, Set.of()).size();
            boolean visitData = pair >= VISIT_DATA_MIN_PAIR && baseVisitors.getOrDefault(base.code(), 0L) >= VISIT_DATA_MIN_BASE;
            exposures.add(new Exposure(visitor.index(), displayId, base.code(), store, rank, page,
                    visitData ? "visit_data" : "local_popular",
                    visitData ? "visit_data" : LOCAL_POPULAR_REASONS[random.nextInt(LOCAL_POPULAR_REASONS.length)],
                    shownAt));
        }
    }

    /** 기간(예약 계획과 같은 기간) 안 10 ~ 22시(운영 시간대)의 임의 시각. */
    private Instant randomTime() {
        LocalDate day = period.start().plusDays(random.nextInt(period.days()));
        LocalTime time = LocalTime.of(10, 0).plusMinutes(between(0, 12 * 60));
        return LocalDateTime.of(day, time).atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
    }

    /** 방문자가 생긴 뒤의 시각. */
    private Instant later(Instant at, Instant notBefore) {
        return at.isAfter(notBefore) ? at : notBefore.plus(Duration.ofMinutes(between(1, 600)));
    }

    private String uuid() {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
