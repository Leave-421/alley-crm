package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /** 회원이 가진 한 가게의 쿠폰. 회원 조건을 함께 건다(다른 회원 쿠폰은 찾지 못한다). */
    List<Coupon> findByCustomerIdAndTargetStoreIdAndStatusInOrderByDownloadedAtDesc(
            Long customerId, Long targetStoreId, Collection<CouponStatus> statuses);
}
