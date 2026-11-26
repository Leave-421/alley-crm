package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.CouponPolicy;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {

    /** 가게의 진행 중인 행사(0 ~ 1개). */
    Optional<CouponPolicy> findByStoreIdAndStoppedAtIsNull(Long storeId);

    /** 가게 조건을 함께 건다. 다른 가게 행사는 찾지 못한다(IDOR). */
    Optional<CouponPolicy> findByIdAndStoreId(Long id, Long storeId);

    /** 진행 중일 때만 중단한다. 바뀐 행 수가 0이면 이미 중단됐거나 그 가게 행사가 아니다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CouponPolicy p SET p.stoppedAt = :now WHERE p.id = :id AND p.storeId = :storeId AND p.stoppedAt IS NULL")
    int stopIfActive(@Param("id") Long id, @Param("storeId") Long storeId, @Param("now") LocalDateTime now);

    /**
     * 쿠폰 한 장 발급: 진행 중이고 남은 수가 있을 때만 발급 수를 1 올린다(CONTRACTS_API 3.2 · 3.4).
     * 바뀐 행 수가 0이면 중단됐거나 다 나갔다. 이 UPDATE가 행사 행을 잠가 같은 행사의 다운로드가 한 줄로 선다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CouponPolicy p SET p.issuedCount = p.issuedCount + 1 "
            + "WHERE p.id = :id AND p.stoppedAt IS NULL AND p.issuedCount < p.totalQuantity")
    int issueOne(@Param("id") Long id);
}
