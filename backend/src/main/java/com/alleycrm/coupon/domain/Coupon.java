package com.alleycrm.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 회원이 받은 쿠폰(표 coupon, D #134). 다운로드할 때 출처(추천 기준 가게 · 노출)를 함께 저장하고,
 * 나중에 다른 가게에서 같은 가게를 추천받아도 바꾸지 않는다(FR-CPN-10).
 *
 * <p>시각 칸은 UTC 값이다. 적용 · 사용 · 만료(E13 · E14)는 상태 칸을 바꾼다. 지금 쓰는 칸만 매핑한다.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "coupon_policy_id", nullable = false, updatable = false)
    private Long couponPolicyId;

    @Column(name = "target_store_id", nullable = false, updatable = false)
    private Long targetStoreId;

    @Column(name = "source_base_store_id", updatable = false)
    private Long sourceBaseStoreId;

    @Column(name = "source_exposure_id", updatable = false)
    private Long sourceExposureId;

    @Column(name = "downloaded_at", nullable = false, updatable = false)
    private LocalDateTime downloadedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "status", nullable = false)
    private CouponStatus status;

    protected Coupon() {
    }

    /** 다운로드(FR-CPN-03 · 05 · 10). 만료 시각 = 다운로드 시각 + 행사의 쿠폰 기간 × 24시간. */
    public static Coupon download(long customerId, CouponPolicy event, long sourceBaseStoreId, long sourceExposureId,
            LocalDateTime now) {
        Coupon coupon = new Coupon();
        coupon.customerId = customerId;
        coupon.couponPolicyId = event.getId();
        coupon.targetStoreId = event.getStoreId();
        coupon.sourceBaseStoreId = sourceBaseStoreId;
        coupon.sourceExposureId = sourceExposureId;
        coupon.downloadedAt = now;
        coupon.expiresAt = now.plusHours(24L * event.getValidDays());
        coupon.status = CouponStatus.DOWNLOADED;
        return coupon;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getCouponPolicyId() {
        return couponPolicyId;
    }

    public Long getTargetStoreId() {
        return targetStoreId;
    }

    public Long getSourceBaseStoreId() {
        return sourceBaseStoreId;
    }

    public Long getSourceExposureId() {
        return sourceExposureId;
    }

    public LocalDateTime getDownloadedAt() {
        return downloadedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public CouponStatus getStatus() {
        return status;
    }
}
