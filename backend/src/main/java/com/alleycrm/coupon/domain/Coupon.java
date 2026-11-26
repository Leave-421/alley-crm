package com.alleycrm.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

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

    /** 발급 주체(D24). 가게 쿠폰은 store, 상인회 쿠폰(E20)은 association. */
    @Column(name = "issuer_kind", nullable = false, updatable = false)
    private CouponIssuer issuerKind;

    @Column(name = "coupon_policy_id", updatable = false)
    private Long couponPolicyId;

    @Column(name = "target_store_id", nullable = false, updatable = false)
    private Long targetStoreId;

    @Column(name = "source_base_store_id", updatable = false)
    private Long sourceBaseStoreId;

    @Column(name = "source_exposure_id", updatable = false)
    private Long sourceExposureId;

    @Column(name = "downloaded_at", nullable = false, updatable = false)
    private Instant downloadedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "status", nullable = false)
    private CouponStatus status;

    @Column(name = "reservation_id")
    private Long reservationId;

    /** 방문 처리 때 정한 실제 할인액(원). 서비스 제공 쿠폰은 0. */
    @Column(name = "discount_applied")
    private Integer discountApplied;

    @Column(name = "used_at")
    private Instant usedAt;

    protected Coupon() {
    }

    /** 신청 시각에 이 쿠폰을 예약에 쓸 수 있는지: '다운로드됨'이고 기한 전(FR-CPN-05, 판단은 신청 시각). */
    public boolean isUsableAt(Instant requestedAt) {
        return status == CouponStatus.DOWNLOADED && requestedAt.isBefore(expiresAt);
    }

    /** 예약에 적용(FR-CPN-06). 쓸 수 있는지는 부르는 쪽이 행을 잠근 뒤 {@link #isUsableAt}으로 확인한다. */
    public void applyTo(long reservationId) {
        if (status != CouponStatus.DOWNLOADED) {
            throw new IllegalStateException("다운로드됨 상태가 아닌 쿠폰은 적용할 수 없다: " + status);
        }
        this.status = CouponStatus.APPLIED;
        this.reservationId = reservationId;
    }

    /** 다운로드(FR-CPN-03 · 05 · 10). 만료 시각 = 다운로드 시각 + 행사의 쿠폰 기간 × 24시간. */
    public static Coupon download(long customerId, CouponPolicy event, long sourceBaseStoreId, long sourceExposureId,
            Instant now) {
        Coupon coupon = new Coupon();
        coupon.customerId = customerId;
        coupon.issuerKind = CouponIssuer.STORE;
        coupon.couponPolicyId = event.getId();
        coupon.targetStoreId = event.getStoreId();
        coupon.sourceBaseStoreId = sourceBaseStoreId;
        coupon.sourceExposureId = sourceExposureId;
        coupon.downloadedAt = now;
        coupon.expiresAt = now.plus(Duration.ofHours(24L * event.getValidDays()));
        coupon.status = CouponStatus.DOWNLOADED;
        return coupon;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public CouponIssuer getIssuerKind() {
        return issuerKind;
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

    public Instant getDownloadedAt() {
        return downloadedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public CouponStatus getStatus() {
        return status;
    }

    /** 방문 처리 때 실제 할인액을 남긴다. 상태는 '예약에 적용됨' 그대로다(FR-CPN-08). */
    public void recordDiscount(int discount) {
        if (status != CouponStatus.APPLIED) {
            throw new IllegalStateException("예약에 적용된 쿠폰만 할인액을 남긴다: " + status);
        }
        this.discountApplied = discount;
    }

    /** 사장님 사용 확인(FR-CPN-08 #1). 방문 처리됐는지는 부르는 쪽이 확인한다. */
    public void markUsed(Instant now) {
        if (status != CouponStatus.APPLIED) {
            throw new IllegalStateException("예약에 적용된 쿠폰만 사용 확인한다: " + status);
        }
        this.status = CouponStatus.USED;
        this.usedAt = now;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Integer getDiscountApplied() {
        return discountApplied;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
