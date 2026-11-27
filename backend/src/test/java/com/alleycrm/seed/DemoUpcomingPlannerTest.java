package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import com.alleycrm.seed.DemoUpcomingPlanner.Report;
import com.alleycrm.seed.DemoUpcomingPlanner.Upcoming;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 다가올 예약 · 변경 신청 · 지연 입력 · 방문 신고(#315). 시드를 돌린 시각을 고정해 DB 없이 확인한다. */
class DemoUpcomingPlannerTest {

    private static final SeedRegionData DATA = SeedDataLoader.loadRegionStores();
    private static final Map<String, SeedRegionData.Store> STORES = DATA.stores().stream()
            .collect(Collectors.toMap(SeedRegionData.Store::code, Function.identity()));
    /** 시드를 돌린 시각: 2026-12-01(화) 14:00 KST. 오늘 남은 슬롯이 있다. */
    private static final Instant NOW = LocalDateTime.of(2026, 12, 1, 14, 0)
            .atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
    private static final DemoPeriod PERIOD = DemoPeriod.at(NOW);
    private static final DemoReservationPlanner.Plan HISTORY = DemoReservationPlanner.plan(DATA.stores(), PERIOD, 600,
            20261211L);
    private static final DemoUpcomingPlanner.Plan PLAN = DemoUpcomingPlanner.plan(DATA.stores(), HISTORY, NOW, 20261214L);
    private static final Map<String, Reservation> HISTORY_BY_NO = HISTORY.reservations().stream()
            .collect(Collectors.toMap(Reservation::reservationNo, Function.identity()));

    @Test
    void 오늘은_서버_시각의_운영_시간대_날짜이고_지난_기록은_어제까지_91일() {
        assertThat(PERIOD).isEqualTo(DemoReservationPlannerTest.PERIOD);
        assertThat(PERIOD.today()).isEqualTo(LocalDate.of(2026, 12, 1));
        // UTC로는 11-30이어도 KST로 12-01 00:30이면 오늘은 12-01
        assertThat(DemoPeriod.at(Instant.parse("2026-11-30T15:30:00Z")).today()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(ChronoUnit.DAYS.between(PERIOD.start(), PERIOD.end()) + 1).isEqualTo(DemoPeriod.HISTORY_DAYS);
        assertThat(PERIOD.lastConfirmedMonth()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void 같은_seed_같은_시각이면_같은_데이터() {
        assertThat(DemoUpcomingPlanner.plan(DATA.stores(), HISTORY, NOW, 20261214L)).isEqualTo(PLAN);
    }

    @Test
    void 지금이_기간의_오늘이_아니면_만들지_않는다() {
        Instant tomorrow = NOW.plus(Duration.ofDays(1));

        assertThatThrownBy(() -> DemoUpcomingPlanner.plan(DATA.stores(), HISTORY, tomorrow, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다가올_예약은_지금_뒤_오늘부터_6일_안이고_신청_확정_시각은_지금_전() {
        assertThat(PLAN.reservations()).hasSizeBetween(25, 40).allSatisfy(u -> {
            SeedRegionData.Store store = STORES.get(u.storeCode());
            LocalDate day = localDate(u.startAt());
            assertThat(u.startAt()).isAfter(NOW);
            assertThat(day).isBetween(PERIOD.today(), PERIOD.today().plusDays(DemoUpcomingPlanner.UPCOMING_DAYS - 1));
            // 신청한 날 기준 예약 가능 기간 안(AvailabilityStep과 같은 규칙)
            assertThat(day).isBeforeOrEqualTo(localDate(u.requestedAt()).plusDays(store.bookingWindowDays()));
            assertThat(u.requestedAt()).isBefore(NOW);
            assertThat(u.status()).isIn(DemoUpcomingPlanner.REQUESTED, DemoUpcomingPlanner.CONFIRMED);
            boolean confirmedOnce = DemoUpcomingPlanner.CONFIRMED.equals(u.status()) || u.changeRequested();
            if (confirmedOnce) {
                assertThat(u.confirmedAt()).isAfter(u.requestedAt()).isBefore(NOW);
            } else {
                assertThat(u.confirmedAt()).isNull();
            }
            assertThat(u.partySize()).isPositive();
            if (store.maxPartySize() != null) {
                assertThat(u.partySize()).isLessThanOrEqualTo(store.maxPartySize());
            }
            assertThat(u.depositAmount()).isEqualTo(store.depositPerPerson() * u.partySize());
        });
    }

    @Test
    void 영업시간_휴무일_슬롯_단위와_슬롯당_팀_수를_지난_기록과_함께_지킨다() {
        Map<String, Integer> teams = new HashMap<>();
        HISTORY.reservations().forEach(r -> teams.merge(r.storeCode() + "@" + r.startAt(), 1, Integer::sum));
        for (Upcoming u : PLAN.reservations()) {
            assertSlot(u.storeCode(), u.startAt());
            teams.merge(u.storeCode() + "@" + u.startAt(), 1, Integer::sum);
            if (u.changeRequested()) {
                assertSlot(u.storeCode(), u.pendingStartAt());
                teams.merge(u.storeCode() + "@" + u.pendingStartAt(), 1, Integer::sum);
            }
        }
        teams.forEach((key, n) -> assertThat(n).as(key)
                .isLessThanOrEqualTo(STORES.get(key.substring(0, key.indexOf('@'))).capacityPerSlot()));
    }

    @Test
    void 가게마다_신청과_확정이_있고_오늘_남은_시간에_확정이_있다() {
        for (SeedRegionData.Store store : DATA.stores()) {
            List<Upcoming> mine = PLAN.reservations().stream().filter(u -> u.storeCode().equals(store.code())).toList();
            assertThat(mine).as(store.code()).anySatisfy(u -> {
                assertThat(u.status()).isEqualTo(DemoUpcomingPlanner.REQUESTED);
                assertThat(u.changeRequested()).isFalse();
            });
            assertThat(mine).as(store.code()).anySatisfy(u ->
                    assertThat(u.status()).isEqualTo(DemoUpcomingPlanner.CONFIRMED));
        }
        assertThat(PLAN.reservations()).filteredOn(u -> DemoUpcomingPlanner.CONFIRMED.equals(u.status())
                && localDate(u.startAt()).equals(PERIOD.today())).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void 변경_신청은_확정됐던_예약이고_바꿀_시각은_다른_빈_슬롯이라_자동_거절에_바로_걸리지_않는다_D8() {
        List<Upcoming> changes = PLAN.reservations().stream().filter(Upcoming::changeRequested).toList();

        assertThat(changes).hasSizeBetween(3, 5).allSatisfy(u -> {
            SeedRegionData.Store store = STORES.get(u.storeCode());
            assertThat(u.status()).isEqualTo(DemoUpcomingPlanner.REQUESTED);
            assertThat(u.confirmedAt()).isNotNull();
            assertThat(u.pendingStartAt()).isNotEqualTo(u.startAt()).isAfter(NOW);
            assertThat(localDate(u.pendingStartAt()))
                    .isBeforeOrEqualTo(PERIOD.today().plusDays(DemoUpcomingPlanner.UPCOMING_DAYS - 1));
            // 자동 거절(D7)은 원래 · 바꿀 시각 중 이른 쪽이 지나야 한다
            Instant deadline = u.startAt().isBefore(u.pendingStartAt()) ? u.startAt() : u.pendingStartAt();
            assertThat(deadline).isAfter(NOW);
            // 신청은 가게 취소 마감 전(24시간 넘게 남음)에 했다
            assertThat(Duration.between(NOW, u.startAt())).isGreaterThan(Duration.ofHours(24));
            assertThat(u.pendingPartySize()).isPositive();
            if (store.maxPartySize() != null) {
                assertThat(u.pendingPartySize()).isLessThanOrEqualTo(store.maxPartySize());
            }
            if (store.depositPerPerson() > 0) {
                assertThat(u.pendingPartySize()).isEqualTo(u.partySize()); // 승인 때 잔액 차감이 없게
            }
            assertThat(u.delayEta()).isNull();
        });
        assertThat(changes).anySatisfy(u -> assertThat(u.storeCode()).isEqualTo(DemoAccountSeeder.OWNER_STORE_CODE));
    }

    @Test
    void 지연_입력은_오늘_확정_예약에만_예약_시각_10_30분_뒤_D14() {
        List<Upcoming> delayed = PLAN.reservations().stream().filter(u -> u.delayEta() != null).toList();

        assertThat(delayed).hasSize(DemoUpcomingPlanner.DELAYS).allSatisfy(u -> {
            assertThat(u.status()).isEqualTo(DemoUpcomingPlanner.CONFIRMED);
            assertThat(u.changeRequested()).isFalse();
            assertThat(localDate(u.startAt())).isEqualTo(PERIOD.today());
            assertThat(Duration.between(u.startAt(), u.delayEta()))
                    .isBetween(Duration.ofMinutes(10), Duration.ofMinutes(30));
        });
    }

    @Test
    void 방문_신고는_최근_방문한_회원_예약에_방문_처리_뒤_지금_전_FR_STL_08() {
        Instant lookback = PERIOD.today().minusDays(DemoUpcomingPlanner.REPORT_LOOKBACK_DAYS)
                .atStartOfDay(DemoReservationPlanner.SERVICE_ZONE).toInstant();
        Set<String> reported = new HashSet<>();

        assertThat(PLAN.reports()).hasSize(DemoUpcomingPlanner.REPORTS).allSatisfy(report -> {
            Reservation r = HISTORY_BY_NO.get(report.reservationNo());
            assertThat(r).isNotNull();
            assertThat(reported.add(report.reservationNo())).isTrue(); // 예약 한 건에 같은 종류 신고 하나
            assertThat(r.status()).isEqualTo("visited");
            assertThat(report.customerIndex()).isEqualTo(r.customerIndex());
            assertThat(HISTORY.customers().get(r.customerIndex()).type()).isEqualTo("member");
            assertThat(r.visitedAt()).isAfterOrEqualTo(lookback);
            assertThat(report.createdAt()).isAfter(r.visitedAt()).isBefore(NOW);
            assertThat(report.type()).isEqualTo(DemoUpcomingPlanner.REPORT_AMOUNT);
            assertThat(report.status()).isIn(DemoUpcomingPlanner.REPORT_RECEIVED,
                    DemoUpcomingPlanner.REPORT_OWNER_CHECK_REQUESTED);
            if (report.claimedAmount() != null) {
                assertThat(report.claimedAmount()).isNotNegative();
            }
            // 정산 확정된 달의 예약에는 신고를 받지 않는다
            assertThat(YearMonth.from(r.startAt().atZone(DemoReservationPlanner.SERVICE_ZONE)))
                    .isAfter(PERIOD.lastConfirmedMonth());
        });
        assertThat(PLAN.reports()).extracting(Report::status)
                .contains(DemoUpcomingPlanner.REPORT_RECEIVED, DemoUpcomingPlanner.REPORT_OWNER_CHECK_REQUESTED);
        // 사장님은 확인 요청받은 신고만 본다: 시연 사장님 가게에 하나
        assertThat(PLAN.reports()).anySatisfy(report -> {
            assertThat(report.status()).isEqualTo(DemoUpcomingPlanner.REPORT_OWNER_CHECK_REQUESTED);
            assertThat(HISTORY_BY_NO.get(report.reservationNo()).storeCode()).isEqualTo(DemoAccountSeeder.OWNER_STORE_CODE);
        });
    }

    @Test
    void 예약번호는_지난_기록과_겹치지_않는다() {
        Set<String> numbers = new HashSet<>(HISTORY_BY_NO.keySet());

        assertThat(PLAN.reservations()).allSatisfy(u -> {
            assertThat(u.reservationNo()).matches("[A-HJ-NP-Z2-9]{8}");
            assertThat(numbers.add(u.reservationNo())).isTrue();
        });
    }

    @Test
    void 밤늦게_돌리면_오늘_슬롯은_없고_지연_입력도_없다() {
        Instant late = LocalDateTime.of(2026, 12, 1, 23, 50).atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();

        DemoUpcomingPlanner.Plan plan = DemoUpcomingPlanner.plan(DATA.stores(), HISTORY, late, 20261214L);

        assertThat(plan.reservations()).isNotEmpty().allSatisfy(u -> {
            assertThat(localDate(u.startAt())).isAfter(PERIOD.today());
            assertThat(u.delayEta()).isNull();
            assertThat(u.requestedAt()).isBefore(late);
        });
        assertThat(plan.reports()).allSatisfy(r -> assertThat(r.createdAt()).isBefore(late));
    }

    private static void assertSlot(String storeCode, Instant at) {
        SeedRegionData.Store store = STORES.get(storeCode);
        LocalDateTime start = LocalDateTime.ofInstant(at, DemoReservationPlanner.SERVICE_ZONE);
        LocalTime time = start.toLocalTime();
        assertThat(store.closedDays()).doesNotContain(DemoReservationPlanner.dayCode(start.getDayOfWeek()));
        assertThat(time).isAfterOrEqualTo(store.openTime());
        assertThat(time.plusMinutes(store.slotMinutes())).isBeforeOrEqualTo(store.closeTime());
        assertThat(ChronoUnit.MINUTES.between(store.openTime(), time) % store.slotMinutes()).isZero();
    }

    private static LocalDate localDate(Instant at) {
        return LocalDate.ofInstant(at, DemoReservationPlanner.SERVICE_ZONE);
    }
}
