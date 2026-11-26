package com.alleycrm.recommend.todaycourse;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.RecommendationBadge;
import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CardStore;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Cell;
import com.alleycrm.recommend.todaycourse.TodayCourseCalculator.Route;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 오늘의 코스(E10 #52, CONTRACTS_API 3.7). 기준 칸은 서버 지금 시각이고 요청자에 따라 달라지지 않는다. 기록하지 않는다. */
@Service
public class TodayCourseService {

    /** {@code GET /api/v1/today-courses} 응답. */
    public record TodayCoursesResponse(DayType dayType, TimeBand timeBand, List<TodayCourse> items) {
    }

    /** 코스 한 개. 손님 수는 넣지 않는다(FR-CRS-12 #1). 도보 정보는 프론트(FR-MAP-08). */
    public record TodayCourse(int rank, List<CardStore> stores, RecommendationBadge badge, int couponStoreCount) {
    }

    private final TodayCourseRepository repository;
    private final Clock clock;

    public TodayCourseService(TodayCourseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TodayCoursesResponse todayCourses(long regionId) {
        if (!repository.regionExists(regionId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Cell now = Cell.at(clock.instant());
        List<Route> routes = TodayCourseCalculator.select(repository.findVisitsInRegion(regionId), now,
                repository.findBookableStores(regionId));
        List<Long> ids = routes.stream().flatMap(r -> r.storeIds().stream()).distinct().toList();
        Map<Long, CardStore> stores = repository.findStores(ids);
        Set<Long> withCoupon = repository.findStoresWithCouponBenefit(ids);
        List<TodayCourse> items = new ArrayList<>();
        for (Route route : routes) {
            items.add(new TodayCourse(items.size() + 1, route.storeIds().stream().map(stores::get).toList(),
                    RecommendationBadge.VISIT_DATA, (int) route.storeIds().stream().filter(withCoupon::contains).count()));
        }
        return new TodayCoursesResponse(now.dayType(), now.timeBand(), items);
    }
}
