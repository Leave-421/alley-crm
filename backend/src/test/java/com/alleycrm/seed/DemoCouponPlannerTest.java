package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.seed.DemoCouponPlanner.Case;
import com.alleycrm.seed.DemoCouponPlanner.Coupon;
import com.alleycrm.seed.DemoCouponPlanner.Event;
import com.alleycrm.seed.DemoCouponPlanner.SourceExposure;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 가상 데이터 2의 쿠폰 시연 사례(E17, NFR-08 #5). DB 없이 확인한다. */
class DemoCouponPlannerTest {

    private static final SeedRegionData DATA = SeedDataLoader.loadRegionStores();
    private static final DemoReservationPlanner.Plan RESERVATIONS = DemoReservationPlanner.plan(DATA.stores(), 600, 20261211L);
    private static final DemoExplorationPlanner.Plan EXPLORATION = DemoExplorationPlanner.plan(DATA.stores(), RESERVATIONS, 20261212L);
    private static final DemoCouponPlanner.Plan PLAN = DemoCouponPlanner.plan(DATA.stores(), RESERVATIONS, EXPLORATION, 20261213L);
    private static final Map<String, Event> EVENTS = PLAN.events().stream()
            .collect(Collectors.toMap(Event::key, Function.identity()));
    private static final Map<String, Reservation> RESERVATION_BY_NO = RESERVATIONS.reservations().stream()
            .collect(Collectors.toMap(Reservation::reservationNo, Function.identity()));

    @Test
    void 같은_seed면_같은_데이터() {
        assertThat(DemoCouponPlanner.plan(DATA.stores(), RESERVATIONS, EXPLORATION, 20261213L)).isEqualTo(PLAN);
    }

    @Test
    void 시연_사례가_모두_있다_NFR_08_5() {
        assertThat(PLAN.events()).extracting(Event::benefitType)
                .contains("fixed_discount", "percent_discount", "service");
        assertThat(PLAN.events()).anySatisfy(e -> assertThat(e.stoppedAt()).isNotNull()); // 중단한 행사(FR-CPN-11)
        // 다운로드 · 기한 만료 · 적용 · 사용 완료 · 적용 취소
        assertThat(PLAN.coupons()).extracting(Coupon::kind).contains(Case.values());
        // 다른 기준 가게에서 중복 차단(30일 제한)
        assertThat(PLAN.blockedRevisits()).isNotEmpty();
    }

    @Test
    void 받은_시각은_행사_기간_안이고_같은_행사는_30일에_한_번_FR_CPN_04() {
        Map<String, List<Coupon>> byCustomerEvent = PLAN.coupons().stream()
                .collect(Collectors.groupingBy(c -> c.customerIndex() + "/" + c.eventKey()));
        for (List<Coupon> mine : byCustomerEvent.values()) {
            List<Coupon> sorted = new ArrayList<>(mine);
            sorted.sort((a, b) -> a.downloadedAt().compareTo(b.downloadedAt()));
            for (int i = 1; i < sorted.size(); i++) {
                assertThat(Duration.between(sorted.get(i - 1).downloadedAt(), sorted.get(i).downloadedAt()))
                        .isGreaterThanOrEqualTo(DemoCouponPlanner.REDOWNLOAD_WAIT);
            }
        }
        assertThat(PLAN.coupons()).allSatisfy(c -> {
            Event e = EVENTS.get(c.eventKey());
            assertThat(e.issuableAt(c.downloadedAt())).isTrue();
            assertThat(c.storeCode()).isEqualTo(e.storeCode());
            assertThat(c.expiresAt()).isEqualTo(c.downloadedAt().plus(Duration.ofDays(e.validDays())));
        });
        Map<String, Long> issued = PLAN.coupons().stream()
                .collect(Collectors.groupingBy(Coupon::eventKey, Collectors.counting()));
        assertThat(PLAN.events()).allSatisfy(e ->
                assertThat(issued.getOrDefault(e.key(), 0L)).isLessThanOrEqualTo(e.totalQuantity()));
    }

    @Test
    void 적용된_쿠폰은_그_가게_그_손님_예약에_기한_안에_신청했고_할인액은_행사_규칙대로() {
        Set<String> referrals = RESERVATIONS.courseLinks().stream().filter(DemoReservationPlanner.CourseLink::referral)
                .map(DemoReservationPlanner.CourseLink::reservationNo).collect(Collectors.toSet());
        Set<String> used = new HashSet<>();
        for (Coupon c : PLAN.coupons()) {
            if (c.reservationNo() == null) {
                assertThat(c.kind()).isIn(Case.EXPIRED, Case.DOWNLOADED);
                assertThat(c.expiresAt().isAfter(DemoCouponPlanner.DATA_END)).isEqualTo(c.kind() == Case.DOWNLOADED);
                continue;
            }
            Reservation r = RESERVATION_BY_NO.get(c.reservationNo());
            assertThat(used.add(c.reservationNo())).isTrue(); // 예약 한 건에 쿠폰 한 장
            assertThat(referrals).doesNotContain(c.reservationNo()); // 소개 예약에는 붙이지 않음(수수료 기록과 맞춤)
            assertThat(r.storeCode()).isEqualTo(c.storeCode());
            assertThat(r.customerIndex()).isEqualTo(c.customerIndex());
            assertThat(r.requestedAt()).isAfter(c.downloadedAt()).isBefore(c.expiresAt()); // 기한은 신청 시각(FR-CPN-05)
            switch (c.kind()) {
                case USED, APPLIED -> {
                    assertThat(r.status()).isEqualTo("visited");
                    assertThat(c.discountApplied()).isEqualTo(EVENTS.get(c.eventKey()).discountFor(r.visitAmount()));
                    if (c.kind() == Case.USED) {
                        assertThat(c.usedAt()).isAfter(r.visitedAt());
                    } else {
                        assertThat(c.usedAt()).isNull();
                    }
                }
                case APPLY_CANCELED -> {
                    assertThat(r.cancelReason()).isEqualTo("customer_canceled");
                    assertThat(c.discountApplied()).isNull();
                }
                default -> throw new AssertionError("예약이 있는 사례가 아님: " + c.kind());
            }
        }
    }

    @Test
    void 쿠폰은_추천_카드를_본_뒤에_받았고_중복_차단_노출은_받은_뒤_30일_안_다른_기준_가게() {
        Map<String, SourceExposure> sources = PLAN.exposures().stream()
                .collect(Collectors.toMap(SourceExposure::key, Function.identity()));
        assertThat(PLAN.coupons()).allSatisfy(c -> {
            SourceExposure e = sources.get(c.exposureKey());
            assertThat(e.shownAt()).isBefore(c.downloadedAt());
            assertThat(e.recommendedStoreCode()).isEqualTo(c.storeCode());
            assertThat(e.baseStoreCode()).isEqualTo(c.baseStoreCode()).isNotEqualTo(c.storeCode());
        });
        Map<Integer, Integer> customerOfVisitor = EXPLORATION.visitors().stream()
                .filter(v -> v.customerIndex() != null)
                .collect(Collectors.toMap(DemoExplorationPlanner.Visitor::index, DemoExplorationPlanner.Visitor::customerIndex));
        assertThat(PLAN.blockedRevisits()).allSatisfy(b -> assertThat(PLAN.coupons()).anySatisfy(c -> {
            assertThat(c.customerIndex()).isEqualTo(customerOfVisitor.get(b.visitorIndex()));
            assertThat(c.storeCode()).isEqualTo(b.recommendedStoreCode());
            assertThat(b.baseStoreCode()).isNotEqualTo(c.baseStoreCode());
            assertThat(Duration.between(c.downloadedAt(), b.shownAt())).isPositive().isLessThan(DemoCouponPlanner.REDOWNLOAD_WAIT);
        }));
    }
}
