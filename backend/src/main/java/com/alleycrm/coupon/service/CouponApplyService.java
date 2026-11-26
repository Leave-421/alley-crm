package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.dto.UsableCouponsResponse;
import com.alleycrm.coupon.repository.CouponLimitRepository;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 적용(E13, CONTRACTS_API 3.5). 예약 화면은 쿠폰을 자동으로 고르지 않고, 회원이 고른 쿠폰 하나만 신청이 저장될 때
 * '예약에 적용됨'이 된다(FR-CPN-06, 11-25 (3)). 기한은 신청 시각으로 판단한다(FR-CPN-05).
 */
@Service
public class CouponApplyService {

    private final CouponRepository coupons;
    private final CouponPolicyRepository events;
    private final CouponLimitRepository limits;
    private final CustomerService customers;
    private final Clock clock;

    public CouponApplyService(CouponRepository coupons, CouponPolicyRepository events, CouponLimitRepository limits,
            CustomerService customers, Clock clock) {
        this.coupons = coupons;
        this.events = events;
        this.limits = limits;
        this.customers = customers;
        this.clock = clock;
    }

    /** 예약 화면의 사용 가능한 쿠폰(FR-CPN-06 #1). 손님에게 보이지 않는 가게면 404. 서버가 고르지 않는다. */
    @Transactional(readOnly = true)
    public UsableCouponsResponse usable(long memberAccountId, long storeId) {
        if (limits.findVisibleStore(storeId).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        long customerId = customers.identifyMember(memberAccountId);
        List<Coupon> usable = coupons.findByCustomerIdAndTargetStoreIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(
                customerId, storeId, CouponStatus.DOWNLOADED, clock.instant().truncatedTo(ChronoUnit.MICROS));
        Map<Long, CouponPolicy> eventsById = events.findAllById(
                        usable.stream().map(Coupon::getCouponPolicyId).distinct().toList())
                .stream().collect(Collectors.toMap(CouponPolicy::getId, Function.identity()));
        return new UsableCouponsResponse(usable.stream()
                .map(coupon -> CouponResponse.of(coupon, eventsById.get(coupon.getCouponPolicyId())))
                .toList());
    }

    /**
     * 예약 신청 단계 60: 회원이 고른 쿠폰을 잠그고 쓸 수 있는지 확인한다. 이 회원의 쿠폰이 아니면 404,
     * 다른 가게 · 다운로드됨이 아님 · 기한이 지남(신청 시각 기준)이면 409.
     */
    @Transactional
    public void checkForRequest(long customerId, long couponId, long storeId, Instant requestedAt) {
        Coupon coupon = coupons.findWithLockByIdAndCustomerId(couponId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (coupon.getTargetStoreId() != storeId || !coupon.isUsableAt(requestedAt)) {
            throw new ApiException(ErrorCode.CONFLICT, "이 예약에 쓸 수 없는 쿠폰입니다.");
        }
    }

    /** 예약 신청 단계 90: 단계 60에서 확인 · 잠근 쿠폰을 저장된 예약에 적용하고, 예약 응답의 {@code coupon}을 만든다. */
    @Transactional
    public AppliedCoupon applyToReservation(long customerId, long couponId, long reservationId) {
        Coupon coupon = coupons.findWithLockByIdAndCustomerId(couponId, customerId)
                .orElseThrow(() -> new IllegalStateException("단계 60에서 확인한 쿠폰이 없다: " + couponId));
        coupon.applyTo(reservationId);
        // 같은 신청 트랜잭션에서 SQL로 쿠폰을 읽는 코드도 적용된 상태를 보도록 바로 반영한다
        coupons.saveAndFlush(coupon);
        return toApplied(coupon);
    }

    /**
     * 예약에 적용 · 사용된 쿠폰(3.5 모양). 없으면 null. 사장님 예약 응답(D3 · D4 · D11)이 {@code coupon} 칸을 채울 때 부른다
     * (CONTRACTS_API 3.6 S-1).
     */
    @Transactional(readOnly = true)
    public AppliedCoupon appliedCouponFor(long reservationId) {
        return coupons.findByReservationIdAndStatusIn(reservationId, List.of(CouponStatus.APPLIED, CouponStatus.USED))
                .filter(coupon -> coupon.getCouponPolicyId() != null) // 상인회 쿠폰은 E20이 더한다
                .map(this::toApplied)
                .orElse(null);
    }

    /** 방문 전 취소 · 거절된 예약의 적용 쿠폰을 '적용 취소'로(FR-CPN-05 #4). 다른 예약에 다시 주지 않는다. */
    @Transactional
    public void cancelForReservation(long reservationId) {
        coupons.changeStatusOfReservation(reservationId, CouponStatus.APPLIED, CouponStatus.APPLY_CANCELED);
    }

    private AppliedCoupon toApplied(Coupon coupon) {
        CouponPolicy event = events.findById(coupon.getCouponPolicyId())
                .orElseThrow(() -> new IllegalStateException("쿠폰의 행사가 없다: " + coupon.getCouponPolicyId()));
        return new AppliedCoupon(
                String.valueOf(coupon.getId()),
                event.getBenefitType().code(),
                event.getDiscountAmount(),
                event.getDiscountPercent(),
                event.getMaxDiscountAmount(),
                event.getServiceName());
    }

}
