package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.AssociationCouponEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssociationCouponEventRepository extends JpaRepository<AssociationCouponEvent, Long> {

    /** 그 권역 행사 전부, 최근에 만든 순. */
    List<AssociationCouponEvent> findByRegionIdOrderByIdDesc(Long regionId);

    /** 권역 조건을 함께 건다. 다른 권역 행사는 찾지 못한다(IDOR). */
    Optional<AssociationCouponEvent> findByIdAndRegionId(Long id, Long regionId);

    /**
     * 상인회 쿠폰 한 장 받기: 중단 안 됨 · 받을 수 있는 기간 안 · 남은 수가 있을 때만 받은 수를 1 올린다(CONTRACTS_API 3.8
     * 「발급 수 늘리기」). 바뀐 행 수가 0이면 마감 · 중단 · 기간 밖. 이 UPDATE가 행사 행을 잠가 같은 행사의 받기가 한 줄로 선다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AssociationCouponEvent e SET e.issuedCount = e.issuedCount + 1 "
            + "WHERE e.id = :id AND e.stoppedAt IS NULL AND e.issuedCount < e.totalQuantity "
            + "AND e.issueStartAt <= :now AND e.issueEndAt > :now")
    int issueOne(@Param("id") Long id, @Param("now") Instant now);

    /** 진행 중일 때만 중단한다. 바뀐 행 수가 0이면 이미 중단됐거나 그 권역 행사가 아니다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AssociationCouponEvent e SET e.stoppedAt = :now "
            + "WHERE e.id = :id AND e.regionId = :regionId AND e.stoppedAt IS NULL")
    int stopIfActive(@Param("id") Long id, @Param("regionId") Long regionId, @Param("now") Instant now);
}
