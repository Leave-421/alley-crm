package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.domain.CouponStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /** 회원이 가진 한 가게의 쿠폰. 회원 조건을 함께 건다(다른 회원 쿠폰은 찾지 못한다). */
    List<Coupon> findByCustomerIdAndTargetStoreIdAndStatusInOrderByDownloadedAtDesc(
            Long customerId, Long targetStoreId, Collection<CouponStatus> statuses);

    /** 예약 화면의 사용 가능한 쿠폰: 그 가게의 이 회원 쿠폰 중 한 상태이고 기한이 남은 것, 만료가 가까운 순. */
    List<Coupon> findByCustomerIdAndTargetStoreIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(
            Long customerId, Long targetStoreId, CouponStatus status, Instant now);

    /** 예약 신청의 쿠폰: 이 회원의 것만 찾고 행을 잠근다. 같은 쿠폰으로 동시에 신청해도 한 신청만 통과한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /** 이 회원의 한 종류 쿠폰 중 그 상태이고 기한 전인 것(예약 화면 목록, 3.10 상인회 쿠폰). */
    List<Coupon> findByCustomerIdAndIssuerKindAndStatusAndExpiresAtAfter(Long customerId, CouponIssuer issuerKind,
            CouponStatus status, Instant now);

    /** 이 회원이 받은 상인회 쿠폰(한 행사에 한 장, uk_coupon_association_member). */
    Optional<Coupon> findByAssociationEventIdAndCustomerId(Long associationEventId, Long customerId);

    Optional<Coupon> findWithLockByIdAndCustomerId(Long id, Long customerId);

    /** 예약의 쿠폰(그 상태 중 하나)을 잠가 찾는다. 예약 한 건에 적용 · 사용된 쿠폰은 하나다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Coupon> findWithLockByReservationIdAndStatusIn(Long reservationId, Collection<CouponStatus> statuses);

    /** 예약의 쿠폰(그 상태 중 하나). 잠그지 않는다. */
    Optional<Coupon> findByReservationIdAndStatusIn(Long reservationId, Collection<CouponStatus> statuses);

    // 아래 일괄 변경은 다른 담당의 트랜잭션(예약 상태 변경 · 가게 상태 변경) 안에서도 불린다.
    // 그 트랜잭션이 들고 있는 엔티티를 떼어 내지 않도록 영속성 컨텍스트를 비우지 않는다(clearAutomatically = false).

    /** 예약의 쿠폰을 from → to로(방문 전 취소: 적용됨 → 적용 취소, 늦은 방문 처리: 적용 취소 → 적용됨). 바뀐 행 수. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.status = :to WHERE c.reservationId = :reservationId AND c.status = :from")
    int changeStatusOfReservation(@Param("reservationId") Long reservationId,
            @Param("from") CouponStatus from, @Param("to") CouponStatus to);

    /**
     * 만료 대상(그 상태이고 만료 시각이 지금이거나 지남)을 잠그고 읽는다. 읽은 뒤 예약에 적용되는 쿠폰과 겹치지 않게 한다(3.6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Coupon> findByStatusAndExpiresAtLessThanEqualOrderByIdAsc(CouponStatus status, Instant now);

    /** 가게가 대상인 쿠폰 중 그 상태들을 '적용 취소'로(운영 중단 · 탈퇴). 바뀐 행 수. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.status = :canceled WHERE c.targetStoreId = :storeId AND c.status IN :statuses")
    int cancelForStore(@Param("storeId") Long storeId, @Param("statuses") Collection<CouponStatus> statuses,
            @Param("canceled") CouponStatus canceled);
}
