package com.alleycrm.recommend.todaycourse;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Cell;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Route;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Visit;
import com.alleycrm.reservation.request.SlotRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 오늘의 코스 고르기(05-policy 「오늘의 코스」, FR-CRS-11). 가게 ID: A=1 B=2 C=3 D=4 E=5 F=6 G=7 H=8 I=9. */
class TodayCourseCalculatorTest {

    static final long A = 1, B = 2, C = 3, D = 4, E = 5, F = 6, G = 7, H = 8, I = 9;
    static final LocalDate SATURDAY = LocalDate.of(2026, 11, 28);
    static final LocalDate SUNDAY = LocalDate.of(2026, 11, 29);
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 30);
    static final Cell WEEKEND_EVENING = new Cell(DayType.WEEKEND, TimeBand.EVENING);
    static final Set<Long> ALL_OPEN = Set.of(A, B, C, D, E, F, G, H, I);

    final List<Visit> visits = new ArrayList<>();
    long nextCustomer = 1;
    long nextReservation = 1;

    @Test
    void 토요일_저녁_첫_가게가_겹치면_빠지고_5명_미만은_빠진다_FR_CRS_11_1() {
        customers(12, SATURDAY, 18, A, B, C);
        customers(9, SATURDAY, 18, A, D, E);
        customers(7, SATURDAY, 18, F, B, C);
        customers(4, SATURDAY, 18, G, H, I);

        assertThat(select(WEEKEND_EVENING)).containsExactly(new Route(A, B, C), new Route(F, B, C));
    }

    @Test
    void 주말_칸만_센다_FR_CRS_11_2() {
        customers(8, MONDAY, 18, A, B, C);
        customers(3, SATURDAY, 18, A, B, C);

        assertThat(Cell.at(kst(SUNDAY, 19, 0))).isEqualTo(WEEKEND_EVENING);
        assertThat(select(WEEKEND_EVENING)).isEmpty();
    }

    @Test
    void 예약을_못_받는_가게가_든_동선은_빠지고_다음_순위가_올라온다_FR_CRS_11_3() {
        customers(12, SATURDAY, 18, A, B, C);
        customers(9, SATURDAY, 18, A, D, E);
        customers(7, SATURDAY, 18, F, G, H);

        List<Route> picked = TodayCourseCalculator.select(visits, WEEKEND_EVENING, Set.of(A, C, D, E, F, G, H));

        assertThat(picked).containsExactly(new Route(A, D, E), new Route(F, G, H));
    }

    @Test
    void 지금_칸에_5명_이상_동선이_없으면_없다_FR_CRS_11_4() {
        customers(4, SATURDAY, 18, A, B, C);
        customers(9, SATURDAY, 12, A, D, E); // 점심 칸

        assertThat(select(WEEKEND_EVENING)).isEmpty();
    }

    @Test
    void 셋_다_방문_처리된_손님만_센다_FR_CRS_11_5() {
        customers(8, SATURDAY, 18, A, B, C);
        for (int i = 0; i < 2; i++) { // B 예약을 취소해 방문 처리 목록에 B가 없다
            long customer = nextCustomer++;
            visit(customer, A, kst(SATURDAY, 18, 0));
            visit(customer, C, kst(SATURDAY, 19, 0));
        }

        assertThat(TodayCourseCalculator.countCustomers(visits, WEEKEND_EVENING)).containsEntry(new Route(A, B, C), 8);
    }

    @Test
    void 네_곳을_이어가면_앞뒤_두_동선을_다_센다() {
        customers(5, SATURDAY, 18, A, B, C, D);

        assertThat(TodayCourseCalculator.countCustomers(visits, WEEKEND_EVENING))
                .containsEntry(new Route(A, B, C), 5)
                .containsEntry(new Route(B, C, D), 5);
    }

    @Test
    void 같은_가게가_두_번_든_동선은_세지_않는다() {
        customers(6, SATURDAY, 18, A, B, A);

        assertThat(TodayCourseCalculator.countCustomers(visits, WEEKEND_EVENING)).isEmpty();
    }

    @Test
    void 같은_손님이_다른_날_또_가도_1명이다() {
        long customer = nextCustomer++;
        for (LocalDate day : List.of(SATURDAY, SUNDAY)) {
            visit(customer, A, kst(day, 18, 0));
            visit(customer, B, kst(day, 18, 30));
            visit(customer, C, kst(day, 19, 0));
        }

        assertThat(TodayCourseCalculator.countCustomers(visits, WEEKEND_EVENING)).containsEntry(new Route(A, B, C), 1);
    }

    @Test
    void 동선의_칸은_첫_방문_시간대로_정한다() {
        customers(6, SATURDAY, 16, A, B, C); // 16:00 · 16:30 오후 → 17:00 저녁

        assertThat(select(WEEKEND_EVENING)).isEmpty();
        assertThat(select(new Cell(DayType.WEEKEND, TimeBand.AFTERNOON))).containsExactly(new Route(A, B, C));
    }

    @Test
    void 운영_시간대_날짜로_같은_날을_본다() {
        // KST 23:00 · 다음 날 00:30 · 01:00 — UTC로는 같은 날이지만 KST로는 다른 날이라 동선이 아니다
        long customer = nextCustomer++;
        visit(customer, A, kst(SATURDAY, 23, 0));
        visit(customer, B, kst(SUNDAY, 0, 30));
        visit(customer, C, kst(SUNDAY, 1, 0));

        assertThat(TodayCourseCalculator.countCustomers(visits, new Cell(DayType.WEEKEND, TimeBand.LATE))).isEmpty();
    }

    @Test
    void 동점은_가게_ID_순이고_최대_3개() {
        customers(5, SATURDAY, 18, D, E, F);
        customers(5, SATURDAY, 18, A, C, B);
        customers(5, SATURDAY, 18, A, B, C);
        customers(5, SATURDAY, 18, G, H, I);
        customers(5, SATURDAY, 18, B, C, D);

        assertThat(select(WEEKEND_EVENING))
                .containsExactly(new Route(A, B, C), new Route(B, C, D), new Route(D, E, F));
    }

    // ---- 준비 ----

    private List<Route> select(Cell now) {
        return TodayCourseCalculator.select(visits, now, ALL_OPEN);
    }

    /** {@code count}명이 그날 {@code hour}시부터 30분 간격으로 가게들을 이어 방문. */
    private void customers(int count, LocalDate day, int hour, long... stores) {
        for (int n = 0; n < count; n++) {
            long customer = nextCustomer++;
            for (int i = 0; i < stores.length; i++) {
                visit(customer, stores[i], kst(day, hour, 0).plusSeconds(1800L * i));
            }
        }
    }

    private void visit(long customer, long store, Instant at) {
        visits.add(new Visit(nextReservation++, customer, store, at));
    }

    private static Instant kst(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(SlotRules.SERVICE_ZONE).toInstant();
    }
}
