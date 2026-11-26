package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.AssociationCouponTerms;
import com.alleycrm.coupon.domain.EligibleStoreScope;
import com.alleycrm.coupon.dto.AssociationCouponEventCreateRequest;
import com.alleycrm.coupon.dto.AssociationCouponEventResponse;
import com.alleycrm.coupon.dto.AssociationCouponEventsResponse;
import com.alleycrm.coupon.repository.AssociationCouponEventRepository;
import com.alleycrm.coupon.repository.AssociationCouponReader;
import com.alleycrm.wallet.FundBudgetService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상인회 쿠폰 행사(E18 #161, CONTRACTS_API 3.8, FR-CPN-12). 만들기 = 확정이고, 같은 트랜잭션에서 공동 기금 예산을 잡는다
 * (D20 {@link FundBudgetService#reserve}). 확정한 행사는 고치지 않고 중단만 하며, 예산은 유효기간이 끝날 때 D20이 돌려준다.
 *
 * <p>{@code regionId}는 호출하는 쪽이 로그인한 상인회 계정의 권역으로 정해서 넘긴다(권역 ID를 받지 않는다).
 */
@Service
public class AssociationCouponEventService {

    private final AssociationCouponEventRepository events;
    private final AssociationCouponReader reader;
    private final FundBudgetService budgets;
    private final Clock clock;

    public AssociationCouponEventService(AssociationCouponEventRepository events, AssociationCouponReader reader,
            FundBudgetService budgets, Clock clock) {
        this.events = events;
        this.reader = reader;
        this.budgets = budgets;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssociationCouponEventsResponse list(long regionId) {
        Instant now = now();
        Map<Long, Long> used = reader.usedBudgets(regionId);
        return new AssociationCouponEventsResponse(events.findByRegionIdOrderByIdDesc(regionId).stream()
                .map(e -> AssociationCouponEventResponse.from(e, e.getReservedBudget(), used.getOrDefault(e.getId(), 0L), now))
                .toList());
    }

    /**
     * 새 행사를 만들고 확정한 뒤 기금 예산(수량 × 한 장 최대 할인액)을 잡는다. 기금이 모자라면
     * {@code 409 insufficient_balance}이고 트랜잭션이 되돌아가 행사도 남지 않는다(FR-CPN-12 #2).
     */
    @Transactional
    public AssociationCouponEventResponse create(long regionId, AssociationCouponEventCreateRequest request) {
        Instant now = now();
        AssociationCouponTerms terms = AssociationCouponTermsValidator.toTerms(request, now);
        if (terms.eligibleStoreScope() == EligibleStoreScope.SELECTED
                && reader.approvedStoresInRegion(regionId, terms.storeIds()).size() != terms.storeIds().size()) {
            throw new ApiException(ErrorCode.INVALID, "이 권역의 참여 가게만 고를 수 있습니다.");
        }
        AssociationCouponEvent saved = events.saveAndFlush(AssociationCouponEvent.confirm(regionId, terms, now));
        long budget = budgets.reserve(saved.getId());
        return AssociationCouponEventResponse.from(saved, budget, 0, now);
    }

    /**
     * 행사를 중단한다. 새로 받기만 멈추고 받은 쿠폰 · 예산은 그대로 둔다(FR-CPN-12 #4 · #5).
     * 다른 권역 행사 · 없는 행사는 404, 이미 중단한 행사는 409.
     */
    @Transactional
    public AssociationCouponEventResponse stop(long regionId, long eventId) {
        AssociationCouponEvent event = events.findByIdAndRegionId(eventId, regionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = now();
        if (!event.isActive() || events.stopIfActive(eventId, regionId, now) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 중단한 상인회 쿠폰 행사입니다.");
        }
        AssociationCouponEvent stopped = events.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("중단한 상인회 쿠폰 행사를 다시 읽지 못했습니다: " + eventId));
        long used = reader.usedBudgets(regionId).getOrDefault(eventId, 0L);
        return AssociationCouponEventResponse.from(stopped, stopped.getReservedBudget(), used, now);
    }

    /** DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. 값은 UTC. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
