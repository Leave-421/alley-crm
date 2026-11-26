package com.alleycrm.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 쿠폰 행사(표 coupon_policy, D #134). 행사마다 행 하나이고, 확정한 뒤에는 설정값을 바꾸지 않는다.
 * 사장님은 중단만 할 수 있고(stopped_at), 진행 중인 행사는 가게당 하나다(DB의 uk_coupon_policy_active).
 *
 * <p>시각 칸은 UTC 값이다. {@code active_store_id}는 DB가 계산하는 칸이라 매핑하지 않는다.
 * 모든 칸이 {@code updatable = false}다: 중단은 저장소의 조건부 UPDATE로, 발급 수는 쿠폰 발급(E12 · E9)의
 * 조건부 UPDATE로만 바꾼다.
 */
@Entity
@Table(name = "coupon_policy")
public class CouponPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "benefit_type", nullable = false, updatable = false)
    private BenefitType benefitType;

    @Column(name = "discount_amount", updatable = false)
    private Integer discountAmount;

    /** 정수 퍼센트(10 = 10%). API 이름은 discountPercent. */
    @Column(name = "discount_rate", updatable = false)
    private Integer discountPercent;

    @Column(name = "max_discount_amount", updatable = false)
    private Integer maxDiscountAmount;

    @Column(name = "valid_days", nullable = false, updatable = false)
    private int validDays;

    @Column(name = "service_name", updatable = false)
    private String serviceName;

    @Column(name = "service_value", updatable = false)
    private Integer serviceValue;

    @Column(name = "conditions", updatable = false)
    private String conditions;

    @Column(name = "total_quantity", nullable = false, updatable = false)
    private int totalQuantity;

    @Column(name = "issued_count", nullable = false, updatable = false)
    private int issuedCount;

    @Column(name = "confirmed_at", updatable = false)
    private LocalDateTime confirmedAt;

    @Column(name = "stopped_at", updatable = false)
    private LocalDateTime stoppedAt;

    protected CouponPolicy() {
    }

    /** 새 행사를 만든다. 저장 = 확정이라 확정 시각을 함께 채운다. */
    public static CouponPolicy confirm(long storeId, CouponEventTerms terms, LocalDateTime now) {
        CouponPolicy policy = new CouponPolicy();
        policy.storeId = storeId;
        policy.benefitType = terms.benefitType();
        policy.discountAmount = terms.discountAmount();
        policy.discountPercent = terms.discountPercent();
        policy.maxDiscountAmount = terms.maxDiscountAmount();
        policy.validDays = terms.validDays();
        policy.serviceName = terms.serviceName();
        policy.serviceValue = terms.serviceValue();
        policy.conditions = terms.conditions();
        policy.totalQuantity = terms.totalQuantity();
        policy.issuedCount = 0;
        policy.confirmedAt = now;
        return policy;
    }

    public boolean isActive() {
        return stoppedAt == null;
    }

    public int remainingQuantity() {
        return Math.max(0, totalQuantity - issuedCount);
    }

    /** '예약 혜택' 표시값: 진행 중이고 남은 수가 있으면 true(FR-CPN-02 · 11). */
    public boolean isBenefitAvailable() {
        return isActive() && remainingQuantity() > 0;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public BenefitType getBenefitType() {
        return benefitType;
    }

    public Integer getDiscountAmount() {
        return discountAmount;
    }

    public Integer getDiscountPercent() {
        return discountPercent;
    }

    public Integer getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public int getValidDays() {
        return validDays;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Integer getServiceValue() {
        return serviceValue;
    }

    public String getConditions() {
        return conditions;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public LocalDateTime getStoppedAt() {
        return stoppedAt;
    }
}
