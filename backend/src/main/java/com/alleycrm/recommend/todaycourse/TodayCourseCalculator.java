package com.alleycrm.recommend.todaycourse;

import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.reservation.request.SlotRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 오늘의 코스 고르기(05-policy 「오늘의 코스」, CONTRACTS_API 3.7 「계산」). DB · 시계 없이 값만으로 계산한다.
 * 손님 수는 순위에만 쓰고 결과에 담지 않는다(FR-CRS-12 #1).
 */
final class TodayCourseCalculator {

    /** 작은 묶음 숨김 기준(05-policy 「설정값」, 정해진 값). */
    static final int MIN_CUSTOMERS = 5;
    /** 오늘의 코스 개수(05-policy 「설정값」, 정해진 값). */
    static final int MAX_COURSES = 3;

    /** 방문 처리된 예약 하나. */
    record Visit(long reservationId, long customerId, long storeId, Instant startAt) {
    }

    /** 요일 구분 × 시간대 칸. */
    record Cell(DayType dayType, TimeBand timeBand) {

        static Cell at(Instant instant) {
            LocalDateTime local = LocalDateTime.ofInstant(instant, SlotRules.SERVICE_ZONE);
            return new Cell(DayType.of(local.toLocalDate()), TimeBand.of(local.toLocalTime()));
        }
    }

    /** 방문 순서대로 세 가게. */
    record Route(long first, long second, long third) {

        List<Long> storeIds() {
            return List.of(first, second, third);
        }
    }

    private static final Comparator<Map.Entry<Route, Integer>> ORDER =
            Comparator.<Map.Entry<Route, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparingLong(e -> e.getKey().first())
                    .thenComparingLong(e -> e.getKey().second())
                    .thenComparingLong(e -> e.getKey().third());

    private TodayCourseCalculator() {
    }

    /**
     * 지금 칸의 오늘의 코스(최대 3개, 첫 가게 서로 다름). {@code bookable}은 지금 예약을 받는 가게.
     * 남는 동선이 없으면 빈 목록.
     */
    static List<Route> select(List<Visit> visits, Cell now, Set<Long> bookable) {
        List<Map.Entry<Route, Integer>> counted = countCustomers(visits, now).entrySet().stream()
                .filter(e -> e.getValue() >= MIN_CUSTOMERS)
                .filter(e -> bookable.containsAll(e.getKey().storeIds()))
                .sorted(ORDER)
                .toList();
        List<Route> picked = new ArrayList<>();
        Set<Long> firsts = new HashSet<>();
        for (Map.Entry<Route, Integer> e : counted) {
            if (picked.size() == MAX_COURSES) {
                break;
            }
            if (firsts.add(e.getKey().first())) {
                picked.add(e.getKey());
            }
        }
        return picked;
    }

    /**
     * 동선별 고유 손님 수. 한 손님의 같은 날(운영 시간대) 방문을 시작 시각 · 예약 ID 순으로 놓고 연달아 있는 세 방문을 본다.
     * 동선의 칸은 그날의 요일 구분과 첫 방문의 시간대다. 같은 가게가 두 번 들어간 동선은 세지 않는다.
     */
    static Map<Route, Integer> countCustomers(List<Visit> visits, Cell now) {
        record Day(long customerId, LocalDate date) {
        }
        Map<Day, List<Visit>> byDay = visits.stream().collect(Collectors.groupingBy(
                v -> new Day(v.customerId(), LocalDate.ofInstant(v.startAt(), SlotRules.SERVICE_ZONE))));
        Map<Route, Set<Long>> customers = new HashMap<>();
        for (List<Visit> day : byDay.values()) {
            List<Visit> ordered = day.stream()
                    .sorted(Comparator.comparing(Visit::startAt).thenComparingLong(Visit::reservationId))
                    .toList();
            for (int i = 0; i + 2 < ordered.size(); i++) {
                Visit a = ordered.get(i);
                Visit b = ordered.get(i + 1);
                Visit c = ordered.get(i + 2);
                if (a.storeId() == b.storeId() || b.storeId() == c.storeId() || a.storeId() == c.storeId()) {
                    continue;
                }
                if (!Cell.at(a.startAt()).equals(now)) {
                    continue;
                }
                customers.computeIfAbsent(new Route(a.storeId(), b.storeId(), c.storeId()), r -> new HashSet<>())
                        .add(a.customerId());
            }
        }
        return customers.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }
}
