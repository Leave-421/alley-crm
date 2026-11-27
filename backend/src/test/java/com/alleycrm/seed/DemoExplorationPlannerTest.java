package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.seed.DemoExplorationPlanner.Exposure;
import com.alleycrm.seed.DemoExplorationPlanner.Visitor;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 가상 데이터 2의 탐색 기록(E17, 06-data 9단계): 방문자 · 지도 추천 노출. DB 없이 확인한다. */
class DemoExplorationPlannerTest {

    private static final SeedRegionData DATA = SeedDataLoader.loadRegionStores();
    private static final DemoReservationPlanner.Plan RESERVATIONS = DemoReservationPlanner.plan(DATA.stores(), 600, 20261211L);
    private static final DemoExplorationPlanner.Plan PLAN = DemoExplorationPlanner.plan(DATA.stores(), RESERVATIONS, 20261212L);

    @Test
    void 같은_seed면_같은_데이터() {
        assertThat(DemoExplorationPlanner.plan(DATA.stores(), RESERVATIONS, 20261212L)).isEqualTo(PLAN);
    }

    @Test
    void 예약한_손님마다_방문자가_있고_첫_예약_신청_전에_이어진다_익명_방문자도_있다() {
        Map<Integer, Instant> firstRequest = new HashMap<>();
        RESERVATIONS.reservations().forEach(r ->
                firstRequest.merge(r.customerIndex(), r.requestedAt(), (a, b) -> a.isBefore(b) ? a : b));

        assertThat(PLAN.visitors()).filteredOn(v -> v.customerIndex() != null).hasSize(firstRequest.size())
                .allSatisfy(v -> {
                    assertThat(v.linkedAt()).isBefore(firstRequest.get(v.customerIndex()));
                    assertThat(v.createdAt()).isBeforeOrEqualTo(v.linkedAt());
                });
        assertThat(PLAN.visitors()).filteredOn(v -> v.customerIndex() == null).isNotEmpty()
                .allSatisfy(v -> assertThat(v.linkedAt()).isNull());
        assertThat(PLAN.visitors()).extracting(Visitor::anonId).doesNotHaveDuplicates();
    }

    @Test
    void 노출은_한_묶음_3곳이고_리롤은_4_6위_같은_표시_같은_가게는_한_번() {
        Set<String> unique = new HashSet<>();
        Map<Integer, Visitor> visitors = new HashMap<>();
        PLAN.visitors().forEach(v -> visitors.put(v.index(), v));

        assertThat(PLAN.exposures()).hasSizeGreaterThan(1000).allSatisfy(e -> {
            assertThat(unique.add(e.visitorIndex() + "/" + e.displayId() + "/" + e.recommendedStoreCode())).isTrue();
            assertThat(e.recommendedStoreCode()).isNotEqualTo(e.baseStoreCode());
            assertThat(e.rank()).isBetween(e.page() * 3 + 1, e.page() * 3 + 3);
            assertThat(e.shownAt()).isAfterOrEqualTo(visitors.get(e.visitorIndex()).createdAt());
            assertThat(e.reasonType().equals("visit_data")).isEqualTo(e.badge().equals("visit_data"));
        });
        assertThat(PLAN.exposures()).extracting(Exposure::page).contains(0, 1);
        assertThat(PLAN.exposures()).extracting(Exposure::badge).contains("visit_data", "local_popular");
    }
}
