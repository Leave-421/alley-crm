package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.seed.DemoReservationPlanner.Plan;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 가상 데이터 1 계획(E16, 06-data 「가상 데이터 생성」 2 ~ 5단계, NFR-07 · NFR-08). DB 없이 확인한다. */
class DemoReservationPlannerTest {

    private static final SeedRegionData DATA = SeedDataLoader.loadRegionStores();
    private static final Map<String, SeedRegionData.Store> STORES = DATA.stores().stream()
            .collect(Collectors.toMap(SeedRegionData.Store::code, Function.identity()));
    private static final Plan PLAN = DemoReservationPlanner.plan(DATA.stores(), 600, 20261211L);

    @Test
    void 같은_seed면_같은_데이터() {
        Plan again = DemoReservationPlanner.plan(DATA.stores(), 600, 20261211L);

        assertThat(again).isEqualTo(PLAN);
        assertThat(DemoReservationPlanner.plan(DATA.stores(), 600, 1L)).isNotEqualTo(PLAN);
    }

    @Test
    void 손님_수는_설정값이고_회원과_비회원_성별_연령대가_섞인다() {
        assertThat(PLAN.customers()).hasSize(600);
        assertThat(PLAN.customers()).extracting(DemoReservationPlanner.Customer::type).contains("member", "guest");
        assertThat(PLAN.customers()).filteredOn(c -> c.type().equals("guest"))
                .allSatisfy(c -> assertThat(c.phone()).matches("0100000\\d{4}"))
                .allSatisfy(c -> assertThat(c.name()).isNotBlank());
        assertThat(PLAN.customers()).extracting(DemoReservationPlanner.Customer::ageGroup)
                .contains("age_20_or_under", "age_30s", "age_40s", "age_50_or_over");
        assertThat(PLAN.customers()).extracting(DemoReservationPlanner.Customer::gender).contains("female", "male");
        long twenties = PLAN.customers().stream().filter(c -> "age_20_or_under".equals(c.ageGroup())).count();
        assertThat(twenties).isGreaterThan(600 / 3); // 대학가
        assertThatThrownBy(() -> DemoReservationPlanner.plan(DATA.stores(), 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예약은_학기_중_9월에서_11월이고_충분히_많다_NFR_07_2() {
        assertThat(PLAN.reservations()).hasSizeGreaterThan(1500);
        assertThat(PLAN.reservations()).allSatisfy(r -> assertThat(localDate(r))
                .isBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30)));
        assertThat(PLAN.reservations()).extracting(r -> localDate(r).getMonthValue()).contains(9, 10, 11);
    }

    @Test
    void 가게_영업시간_휴무일_슬롯_슬롯당_팀_수_최대_인원을_지킨다() {
        Map<String, Long> teamsPerSlot = PLAN.reservations().stream()
                .collect(Collectors.groupingBy(r -> r.storeCode() + "@" + r.startAt(), Collectors.counting()));
        for (Reservation r : PLAN.reservations()) {
            SeedRegionData.Store store = STORES.get(r.storeCode());
            LocalDateTime start = local(r);
            assertThat(store.closedDays()).doesNotContain(DemoReservationPlanner.dayCode(start.getDayOfWeek()));
            LocalTime time = start.toLocalTime();
            assertThat(time).isAfterOrEqualTo(store.openTime());
            assertThat(time.plusMinutes(store.slotMinutes())).isBeforeOrEqualTo(store.closeTime());
            assertThat(ChronoUnit.MINUTES.between(store.openTime(), time) % store.slotMinutes()).isZero();
            assertThat(teamsPerSlot.get(r.storeCode() + "@" + r.startAt())).isLessThanOrEqualTo(store.capacityPerSlot());
            assertThat(r.partySize()).isPositive();
            if (store.maxPartySize() != null) {
                assertThat(r.partySize()).isLessThanOrEqualTo(store.maxPartySize());
            }
            assertThat(r.depositAmount()).isEqualTo(store.depositPerPerson() * r.partySize());
        }
    }

    @Test
    void 취소는_10퍼센트_안팎이고_하루_전_당일_가게_거절이_섞인다_NFR_08() {
        List<Reservation> canceled = PLAN.reservations().stream().filter(r -> r.status().equals("canceled")).toList();
        double ratio = (double) canceled.size() / PLAN.reservations().size();
        assertThat(ratio).isBetween(0.07, 0.13);

        assertThat(canceled).extracting(Reservation::cancelReason).containsOnly("customer_canceled", "store_rejected");
        List<Reservation> customerCanceled = canceled.stream()
                .filter(r -> r.cancelReason().equals("customer_canceled")).toList();
        assertThat(customerCanceled).anySatisfy(r ->
                assertThat(ChronoUnit.HOURS.between(r.canceledAt(), r.startAt())).isGreaterThanOrEqualTo(24));
        assertThat(customerCanceled).anySatisfy(r ->
                assertThat(ChronoUnit.HOURS.between(r.canceledAt(), r.startAt())).isLessThan(24));
        assertThat(canceled).allSatisfy(r -> {
            assertThat(r.canceledAt()).isAfter(r.requestedAt()).isBeforeOrEqualTo(r.startAt());
            assertThat(r.visitedAt()).isNull();
            assertThat(r.visitAmount()).isNull();
        });
        assertThat(canceled).filteredOn(r -> r.cancelReason().equals("store_rejected"))
                .allSatisfy(r -> assertThat(r.confirmedAt()).isNull());
    }

    @Test
    void 방문_처리된_예약은_방문_금액이_있고_시각_순서가_맞다() {
        List<Reservation> visited = PLAN.reservations().stream().filter(r -> r.status().equals("visited")).toList();

        assertThat(visited).isNotEmpty().allSatisfy(r -> {
            assertThat(r.cancelReason()).isNull();
            assertThat(r.visitAmount()).isPositive();
            assertThat(r.visitAmount() % 100).isZero();
            assertThat(r.requestedAt()).isBefore(r.confirmedAt());
            assertThat(r.confirmedAt()).isBeforeOrEqualTo(r.startAt());
            assertThat(r.visitedAt()).isAfterOrEqualTo(r.startAt());
        });
    }

    @Test
    void 금요일_저녁_주점_예약이_몰린다_NFR_08_패턴_2() {
        Map<DayOfWeek, Long> eveningPubByDay = PLAN.reservations().stream()
                .filter(r -> STORES.get(r.storeCode()).category() == SeedCategory.PUB)
                .filter(r -> local(r).getHour() >= 18)
                .collect(Collectors.groupingBy(r -> local(r).getDayOfWeek(), Collectors.counting()));
        long friday = eveningPubByDay.getOrDefault(DayOfWeek.FRIDAY, 0L);
        double otherDaysAverage = eveningPubByDay.entrySet().stream()
                .filter(e -> e.getKey() != DayOfWeek.FRIDAY)
                .mapToLong(Map.Entry::getValue).sum() / 6.0;

        assertThat(friday).isGreaterThan((long) (otherDaysAverage * 2));
        assertThat(eveningPubByDay.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey())
                .isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void 예약번호는_8자_겹치지_않는다() {
        Set<String> numbers = new HashSet<>();
        assertThat(PLAN.reservations()).allSatisfy(r -> {
            assertThat(r.reservationNo()).matches("[A-HJ-NP-Z2-9]{8}");
            assertThat(numbers.add(r.reservationNo())).isTrue();
        });
    }

    // ---- 같은 날 연속 방문 (E17, 06-data 6단계) ----

    @Test
    void 이십대는_카페_다음에_디저트를_훨씬_자주_간다_NFR_08_패턴_1() {
        // 추천 실제 기록 점수(E6)와 같은 기준: 카페 방문 중 같은 날 바로 다음 방문이 디저트인 비율
        Map<Boolean, int[]> byTwenties = new HashMap<>(); // [카페 방문 수, 그중 바로 다음이 디저트]
        for (List<Reservation> day : visitedDays().values()) {
            for (int i = 0; i < day.size(); i++) {
                if (STORES.get(day.get(i).storeCode()).category() != SeedCategory.CAFE) {
                    continue;
                }
                boolean twenties = "age_20_or_under".equals(PLAN.customers().get(day.get(i).customerIndex()).ageGroup());
                int[] counts = byTwenties.computeIfAbsent(twenties, k -> new int[2]);
                counts[0]++;
                if (i + 1 < day.size() && STORES.get(day.get(i + 1).storeCode()).category() == SeedCategory.DESSERT) {
                    counts[1]++;
                }
            }
        }
        double twentiesShare = (double) byTwenties.get(true)[1] / byTwenties.get(true)[0];
        double othersShare = (double) byTwenties.get(false)[1] / byTwenties.get(false)[0];

        assertThat(byTwenties.get(true)[1]).isGreaterThan(50);
        assertThat(twentiesShare).isGreaterThan(othersShare * 2);
    }

    @Test
    void 오늘의_코스_칸마다_5명_이상인_3곳_동선이_있다_FR_CRS_11() {
        // 오늘의 코스 계산(3.7)과 같게: 같은 날 방문 처리 예약을 시간순으로, 연달아 있는 서로 다른 세 가게, 첫 방문 칸
        Map<String, Map<List<String>, Set<Integer>>> byCell = new HashMap<>();
        for (List<Reservation> day : visitedDays().values()) {
            for (int i = 0; i + 2 < day.size(); i++) {
                List<String> route = List.of(day.get(i).storeCode(), day.get(i + 1).storeCode(), day.get(i + 2).storeCode());
                if (new HashSet<>(route).size() < 3) {
                    continue;
                }
                LocalDateTime first = local(day.get(i));
                String cell = DayType.of(first.toLocalDate()) + "/" + TimeBand.of(first.toLocalTime());
                byCell.computeIfAbsent(cell, k -> new HashMap<>())
                        .computeIfAbsent(route, k -> new HashSet<>()).add(day.get(i).customerIndex());
            }
        }

        for (String cell : List.of("WEEKDAY/LUNCH", "WEEKDAY/AFTERNOON", "WEEKDAY/EVENING",
                "WEEKEND/LUNCH", "WEEKEND/AFTERNOON", "WEEKEND/EVENING")) {
            long shown = byCell.getOrDefault(cell, Map.of()).entrySet().stream()
                    .filter(e -> e.getValue().size() >= DemoReservationPlanner.COURSE_MIN_CUSTOMERS)
                    .map(e -> e.getKey().get(0))
                    .distinct()
                    .count();
            assertThat(shown).as(cell + " 칸에서 첫 가게가 다른 5명 이상 동선 수").isGreaterThanOrEqualTo(1);
        }
    }

    // ---- 정식 코스 제안 · 코스 예약 · 재방문 (E17, 06-data 8 · 9단계) ----

    @Test
    void 코스_예약은_기준_예약_신청_뒤_제안을_보고_마감_안에_신청하면_소개_후보다_FR_STL_01() {
        Map<String, Reservation> byNo = PLAN.reservations().stream()
                .collect(Collectors.toMap(Reservation::reservationNo, Function.identity()));
        Map<String, DemoReservationPlanner.Proposal> proposals = PLAN.proposals().stream()
                .collect(Collectors.toMap(p -> p.baseReservationNo() + "/" + p.storeCode(), Function.identity()));
        assertThat(proposals).hasSameSizeAs(PLAN.proposals()); // (기준 예약, 가게) 조합당 1건
        assertThat(PLAN.proposals()).allSatisfy(p -> {
            Reservation base = byNo.get(p.baseReservationNo());
            assertThat(p.firstShownAt()).isAfter(base.requestedAt()).isBefore(DemoReservationPlanner.deadline(base));
            assertThat(p.storeCode()).isNotEqualTo(base.storeCode());
        });

        List<DemoReservationPlanner.CourseLink> referrals = PLAN.courseLinks().stream()
                .filter(DemoReservationPlanner.CourseLink::referral).toList();
        assertThat(referrals).hasSizeGreaterThan(20);
        assertThat(PLAN.courseLinks()).filteredOn(l -> !l.referral()).isNotEmpty();
        for (DemoReservationPlanner.CourseLink link : PLAN.courseLinks()) {
            Reservation course = byNo.get(link.reservationNo());
            Reservation base = byNo.get(link.rootReservationNo());
            assertThat(course.storeCode()).isNotEqualTo(base.storeCode());
            assertThat(course.startAt()).isAfter(course.requestedAt().plus(Duration.ofMinutes(59)));
            if (localDate(course).equals(localDate(base))) {
                assertThat(course.startAt()).isAfter(base.startAt()); // 같은 날은 기준 예약보다 늦게(D12)
            }
            if (link.referral()) {
                DemoReservationPlanner.Proposal used = proposals.get(base.reservationNo() + "/" + course.storeCode());
                assertThat(used).isNotNull();
                assertThat(link.proposalStoreCode()).isEqualTo(course.storeCode());
                assertThat(link.senderStoreCode()).isEqualTo(base.storeCode());
                assertThat(course.requestedAt()).isAfter(used.firstShownAt()).isBefore(DemoReservationPlanner.deadline(base));
            } else {
                assertThat(link.proposalStoreCode()).isNull();
                assertThat(course.requestedAt()).isAfterOrEqualTo(DemoReservationPlanner.deadline(base)); // 코스 연결만
            }
        }
    }

    @Test
    void 소개로_방문한_손님은_그_가게를_더_자주_다시_찾는다_NFR_08_패턴_3() {
        Set<String> referralNos = PLAN.courseLinks().stream().filter(DemoReservationPlanner.CourseLink::referral)
                .map(DemoReservationPlanner.CourseLink::reservationNo).collect(Collectors.toSet());
        Map<String, List<Reservation>> visitsByCustomerStore = PLAN.reservations().stream()
                .filter(r -> r.status().equals("visited"))
                .collect(Collectors.groupingBy(r -> r.customerIndex() + "@" + r.storeCode()));
        int[] course = new int[2]; // [방문 수, 60일 안 같은 가게 재방문 수]
        int[] others = new int[2];
        for (List<Reservation> visits : visitsByCustomerStore.values()) {
            for (Reservation r : visits) {
                boolean revisited = visits.stream().anyMatch(o -> o.startAt().isAfter(r.startAt())
                        && !o.startAt().isAfter(r.startAt().plus(Duration.ofDays(60))));
                int[] counts = referralNos.contains(r.reservationNo()) ? course : others;
                counts[0]++;
                if (revisited) {
                    counts[1]++;
                }
            }
        }
        double courseRate = (double) course[1] / course[0];
        double othersRate = (double) others[1] / others[0];

        assertThat(courseRate).isGreaterThan(othersRate * 1.5).isGreaterThan(0.3);
    }

    /** 손님 · 운영 시간대 날짜별 방문 처리 예약(시작 시각 순). */
    private static Map<String, List<Reservation>> visitedDays() {
        Map<String, List<Reservation>> days = new HashMap<>();
        PLAN.reservations().stream()
                .filter(r -> r.status().equals("visited"))
                .sorted(Comparator.comparing(Reservation::startAt))
                .forEach(r -> days.computeIfAbsent(r.customerIndex() + "@" + localDate(r), k -> new ArrayList<>()).add(r));
        return days;
    }

    private static LocalDateTime local(Reservation r) {
        return LocalDateTime.ofInstant(r.startAt(), DemoReservationPlanner.SERVICE_ZONE);
    }

    private static LocalDate localDate(Reservation r) {
        return local(r).toLocalDate();
    }
}
