package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.CouponEventTerms;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.dto.BenefitTypesResponse;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;
import com.alleycrm.coupon.dto.CouponEventResponse;
import com.alleycrm.coupon.dto.CurrentCouponEventResponse;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사장님의 쿠폰 행사 설정(E11, CONTRACTS_API 3.2). 만들기 = 확정이고, 확정한 행사는 고치지 않고 중단만 한다(11-25).
 *
 * <p>{@code storeId}는 호출하는 쪽이 로그인한 사장님 계정의 가게로 정해서 넘긴다(경로의 가게 ID를 받지 않는다).
 */
@Service
public class CouponEventService {

    /** 진행 중인 행사 가게당 하나를 지키는 DB 제약(D #134). */
    static final String ACTIVE_EVENT_CONSTRAINT = "uk_coupon_policy_active";

    private final CouponPolicyRepository repository;
    private final Clock clock;

    public CouponEventService(CouponPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public BenefitTypesResponse benefitTypes() {
        return BenefitTypesResponse.all();
    }

    @Transactional(readOnly = true)
    public CurrentCouponEventResponse current(long storeId) {
        return new CurrentCouponEventResponse(repository.findByStoreIdAndStoppedAtIsNull(storeId)
                .map(CouponEventResponse::from)
                .orElse(null));
    }

    /** 새 행사를 만들고 바로 확정한다. 진행 중인 행사가 있으면 409(FR-CPN-01 #6). */
    @Transactional
    public CouponEventResponse create(long storeId, CouponEventCreateRequest request) {
        CouponEventTerms terms = CouponEventTermsValidator.toTerms(request);
        if (repository.findByStoreIdAndStoppedAtIsNull(storeId).isPresent()) {
            throw activeEventExists();
        }
        try {
            CouponPolicy saved = repository.saveAndFlush(CouponPolicy.confirm(storeId, terms, now()));
            return CouponEventResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 만들기 요청: 먼저 커밋한 쪽만 남는다.
            if (violates(e, ACTIVE_EVENT_CONSTRAINT)) {
                throw activeEventExists();
            }
            throw e;
        }
    }

    /**
     * 행사를 중단한다. 새 발급만 멈추고 이미 받은 쿠폰은 그대로 둔다(FR-CPN-11).
     * 다른 가게 행사 · 없는 행사는 404, 이미 중단한 행사는 409.
     */
    @Transactional
    public CouponEventResponse stop(long storeId, long eventId) {
        CouponPolicy policy = repository.findByIdAndStoreId(eventId, storeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!policy.isActive() || repository.stopIfActive(eventId, storeId, now()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 중단한 쿠폰 행사입니다.");
        }
        return repository.findById(eventId)
                .map(CouponEventResponse::from)
                .orElseThrow(() -> new IllegalStateException("중단한 쿠폰 행사를 다시 읽지 못했습니다: " + eventId));
    }

    /** DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. 값은 UTC. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException activeEventExists() {
        return new ApiException(ErrorCode.CONFLICT, "진행 중인 쿠폰 행사가 있습니다. 중단한 뒤 새로 만들어 주세요.");
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
