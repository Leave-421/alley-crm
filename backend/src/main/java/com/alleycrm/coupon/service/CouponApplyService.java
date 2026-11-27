package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.dto.UsableCouponsResponse;
import com.alleycrm.coupon.repository.AssociationCouponEventRepository;
import com.alleycrm.coupon.repository.AssociationCouponReader;
import com.alleycrm.coupon.repository.CouponLimitRepository;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 적용(E13 · E20, CONTRACTS_API 3.5 · 3.10). 예약 화면은 쿠폰을 자동으로 고르지 않고, 회원이 고른 쿠폰 하나만 신청이
 * 저장될 때 '예약에 적용됨'이 된다(FR-CPN-06, 11-25 (3)). 기한은 신청 시각으로 판단한다(FR-CPN-05).
 * 가게 쿠폰과 상인회 쿠폰을 같은 목록 · 같은 ID로 다루고, 한 예약에는 한 장만 적용한다(FR-CPN-14).
 */
@Service
public class CouponApplyService {

    private final CouponRepository coupons;
    private final CouponPolicyRepository events;
    private final AssociationCouponEventRepository associationEvents;
    private final AssociationCouponReader associationReader;
    private final CouponLimitRepository limits;
    private final CustomerService customers;
    private final Clock clock;

    public CouponApplyService(CouponRepository coupons, CouponPolicyRepository events,
            AssociationCouponEventRepository associationEvents, AssociationCouponReader associationReader,
            CouponLimitRepository limits, CustomerService customers, Clock clock) {
        this.coupons = coupons;
        this.events = events;
        this.associationEvents = associationEvents;
        this.associationReader = associationReader;
        this.limits = limits;
        this.customers = customers;
        this.clock = clock;
    }

    /**
     * 예약 화면의 사용 가능한 쿠폰(FR-CPN-06 #1): 그 가게 쿠폰과 그 가게에서 지금 쓸 수 있는 상인회 쿠폰(3.10),
     * 만료가 가까운 순. 손님에게 보이지 않는 가게면 404. 서버가 고르지 않는다.
     */
    @Transactional(readOnly = true)
    public UsableCouponsResponse usable(long memberAccountId, long storeId) {
        if (limits.findVisibleStore(storeId).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        long customerId = customers.identifyMember(memberAccountId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        record Usable(Coupon coupon, CouponResponse response) {
        }
        List<Usable> usable = new ArrayList<>();
        List<Coupon> storeCoupons = coupons.findByCustomerIdAndTargetStoreIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(
                customerId, storeId, CouponStatus.DOWNLOADED, now);
        Map<Long, CouponPolicy> eventsById = events.findAllById(
                        storeCoupons.stream().map(Coupon::getCouponPolicyId).distinct().toList())
                .stream().collect(Collectors.toMap(CouponPolicy::getId, Function.identity()));
        storeCoupons.forEach(c -> usable.add(new Usable(c, CouponResponse.of(c, eventsById.get(c.getCouponPolicyId())))));

        List<Coupon> associationCoupons = coupons.findByCustomerIdAndIssuerKindAndStatusAndExpiresAtAfter(
                customerId, CouponIssuer.ASSOCIATION, CouponStatus.DOWNLOADED, now);
        if (!associationCoupons.isEmpty()) {
            Long storeRegion = associationReader.approvedStoreRegion(storeId).orElse(null);
            Map<Long, AssociationCouponEvent> associationById = associationEvents.findAllById(
                            associationCoupons.stream().map(Coupon::getAssociationEventId).distinct().toList())
                    .stream().collect(Collectors.toMap(AssociationCouponEvent::getId, Function.identity()));
            for (Coupon c : associationCoupons) {
                AssociationCouponEvent event = associationById.get(c.getAssociationEventId());
                if (event.isUsableAt(storeId, storeRegion, now)) {
                    usable.add(new Usable(c, CouponResponse.of(c, event)));
                }
            }
        }
        return new UsableCouponsResponse(usable.stream()
                .sorted(Comparator.comparing((Usable u) -> u.coupon().getExpiresAt())
                        .thenComparing(u -> u.coupon().getId()))
                .map(Usable::response)
                .toList());
    }

    /**
     * 예약 신청 단계 60: 회원이 고른 쿠폰을 잠그고 쓸 수 있는지 확인한다. 이 회원의 쿠폰이 아니면 404,
     * 그 가게에서 쓸 수 없음(가게 쿠폰은 다른 가게, 상인회 쿠폰은 쓸 수 있는 가게 · 기간 밖) · 다운로드됨이 아님 ·
     * 기한이 지남(신청 시각 기준)이면 409.
     */
    @Transactional
    public void checkForRequest(long customerId, long couponId, long storeId, Instant requestedAt) {
        Coupon coupon = coupons.findWithLockByIdAndCustomerId(couponId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!coupon.isUsableAt(requestedAt) || !usableAtStore(coupon, storeId, requestedAt)) {
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
     * 예약에 적용 · 사용된 쿠폰(3.5 모양, 상인회 쿠폰 포함). 없으면 null. 사장님 예약 응답(D3 · D4 · D11)이 {@code coupon}
     * 칸을 채울 때 부른다(CONTRACTS_API 3.6 S-1).
     */
    @Transactional(readOnly = true)
    public AppliedCoupon appliedCouponFor(long reservationId) {
        return coupons.findByReservationIdAndStatusIn(reservationId, List.of(CouponStatus.APPLIED, CouponStatus.USED))
                .map(this::toApplied)
                .orElse(null);
    }

    /** 방문 전 취소 · 거절된 예약의 적용 쿠폰을 '적용 취소'로(FR-CPN-05 #4). 다른 예약에 다시 주지 않는다. */
    @Transactional
    public void cancelForReservation(long reservationId) {
        coupons.changeStatusOfReservation(reservationId, CouponStatus.APPLIED, CouponStatus.APPLY_CANCELED);
    }

    /** 가게 쿠폰은 대상 가게가 그 가게, 상인회 쿠폰은 쓸 수 있는 가게 · 기간(FR-CPN-14 #2, 3.10). */
    private boolean usableAtStore(Coupon coupon, long storeId, Instant requestedAt) {
        if (coupon.getIssuerKind() == CouponIssuer.ASSOCIATION) {
            return associationEventOf(coupon)
                    .isUsableAt(storeId, associationReader.approvedStoreRegion(storeId).orElse(null), requestedAt);
        }
        return coupon.getTargetStoreId() != null && coupon.getTargetStoreId() == storeId;
    }

    private AppliedCoupon toApplied(Coupon coupon) {
        if (coupon.getIssuerKind() == CouponIssuer.ASSOCIATION) {
            AssociationCouponEvent event = associationEventOf(coupon);
            return new AppliedCoupon(String.valueOf(coupon.getId()), event.getBenefitType().code(),
                    event.getDiscountAmount(), event.getDiscountPercent(), event.getMaxDiscountAmount(), null);
        }
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

    private AssociationCouponEvent associationEventOf(Coupon coupon) {
        return associationEvents.findById(coupon.getAssociationEventId())
                .orElseThrow(() -> new IllegalStateException("쿠폰의 상인회 행사가 없다: " + coupon.getAssociationEventId()));
    }
}
