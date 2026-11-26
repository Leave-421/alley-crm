package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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
            Long customerId, Long targetStoreId, CouponStatus status, LocalDateTime now);

    /** 예약 신청의 쿠폰: 이 회원의 것만 찾고 행을 잠근다. 같은 쿠폰으로 동시에 신청해도 한 신청만 통과한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Coupon> findWithLockByIdAndCustomerId(Long id, Long customerId);

    /** 예약에 적용된 쿠폰을 '적용 취소'로(방문 전 취소 · 거절). 바뀐 행 수. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Coupon c SET c.status = :canceled WHERE c.reservationId = :reservationId AND c.status = :applied")
    int cancelApplied(@Param("reservationId") Long reservationId,
            @Param("applied") CouponStatus applied, @Param("canceled") CouponStatus canceled);
}
