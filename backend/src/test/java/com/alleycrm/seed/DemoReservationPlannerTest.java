package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.seed.DemoReservationPlanner.Plan;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
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

    private static LocalDateTime local(Reservation r) {
        return LocalDateTime.ofInstant(r.startAt(), DemoReservationPlanner.SERVICE_ZONE);
    }

    private static LocalDate localDate(Reservation r) {
        return local(r).toLocalDate();
    }
}
