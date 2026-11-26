package com.alleycrm.coupon.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 상인회 쿠폰 행사(표 association_coupon_event, D24 #159). 상인회가 만들고 할인은 공동 기금이 부담한다(11-25 (5)).
 * 확정한 뒤에는 설정값을 바꾸지 않고 중단만 한다.
 *
 * <p>모든 칸이 {@code updatable = false}다: 중단은 저장소의 조건부 UPDATE로, 받은 수는 E19의 조건부 UPDATE로,
 * 예산(reserved_budget · budget_released_at)은 D20 {@code FundBudgetService}가 바꾼다. 시각은 UTC 값이다.
 */
@Entity
@Table(name = "association_coupon_event")
public class AssociationCouponEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_id", nullable = false, updatable = false)
    private Long regionId;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "benefit_type", nullable = false, updatable = false)
    private BenefitType benefitType;

    @Column(name = "discount_amount", updatable = false)
    private Integer discountAmount;

    /** 정수 퍼센트(10 = 10%). API 이름은 discountPercent. */
    @Column(name = "discount_rate", updatable = false)
    private Integer discountPercent;

    @Column(name = "max_discount_amount", updatable = false)
    private Integer maxDiscountAmount;

    @Column(name = "total_quantity", nullable = false, updatable = false)
    private int totalQuantity;

    @Column(name = "issued_count", nullable = false, updatable = false)
    private int issuedCount;

    @Column(name = "eligible_store_scope", nullable = false, updatable = false)
    private EligibleStoreScope eligibleStoreScope;

    /** 쓸 수 있는 가게(selected일 때만 행이 있다). */
    @ElementCollection
    @CollectionTable(name = "association_coupon_event_store", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "store_id", nullable = false)
    private Set<Long> storeIds = new HashSet<>();

    @Column(name = "issue_start_at", nullable = false, updatable = false)
    private Instant issueStartAt;

    @Column(name = "issue_end_at", nullable = false, updatable = false)
    private Instant issueEndAt;

    @Column(name = "valid_start_at", nullable = false, updatable = false)
    private Instant validStartAt;

    @Column(name = "valid_end_at", nullable = false, updatable = false)
    private Instant validEndAt;

    /** 만들 때 0으로 넣고, 같은 트랜잭션에서 D20 reserve가 채운다. */
    @Column(name = "reserved_budget", nullable = false, updatable = false)
    private long reservedBudget;

    @Column(name = "budget_released_at", insertable = false, updatable = false)
    private Instant budgetReleasedAt;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "stopped_at", updatable = false)
    private Instant stoppedAt;

    protected AssociationCouponEvent() {
    }

    /** 새 행사를 만든다. 저장 = 확정이라 확정 시각을 함께 채운다. 예산은 저장 뒤 D20이 잡는다. */
    public static AssociationCouponEvent confirm(long regionId, AssociationCouponTerms terms, Instant now) {
        AssociationCouponEvent event = new AssociationCouponEvent();
        event.regionId = regionId;
        event.title = terms.title();
        event.benefitType = terms.benefitType();
        event.discountAmount = terms.discountAmount();
        event.discountPercent = terms.discountPercent();
        event.maxDiscountAmount = terms.maxDiscountAmount();
        event.totalQuantity = terms.totalQuantity();
        event.issuedCount = 0;
        event.eligibleStoreScope = terms.eligibleStoreScope();
        event.storeIds = new HashSet<>(terms.storeIds());
        event.issueStartAt = terms.issueStartAt();
        event.issueEndAt = terms.issueEndAt();
        event.validStartAt = terms.validStartAt();
        event.validEndAt = terms.validEndAt();
        event.reservedBudget = 0;
        event.confirmedAt = now;
        return event;
    }

    public boolean isActive() {
        return stoppedAt == null;
    }

    public int remainingQuantity() {
        return Math.max(0, totalQuantity - issuedCount);
    }

    /** 지금 받을 수 있는지: 중단 안 됨 · 받을 수 있는 기간 [시작, 끝) 안 · 남은 수 1 이상(3.8 「받을 수 있음」). */
    public boolean isIssuable(Instant now) {
        return isActive() && !now.isBefore(issueStartAt) && now.isBefore(issueEndAt) && remainingQuantity() > 0;
    }

    public Long getId() {
        return id;
    }

    public Long getRegionId() {
        return regionId;
    }

    public String getTitle() {
        return title;
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

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public EligibleStoreScope getEligibleStoreScope() {
        return eligibleStoreScope;
    }

    public Set<Long> getStoreIds() {
        return storeIds;
    }

    public Instant getIssueStartAt() {
        return issueStartAt;
    }

    public Instant getIssueEndAt() {
        return issueEndAt;
    }

    public Instant getValidStartAt() {
        return validStartAt;
    }

    public Instant getValidEndAt() {
        return validEndAt;
    }

    public long getReservedBudget() {
        return reservedBudget;
    }

    public Instant getBudgetReleasedAt() {
        return budgetReleasedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }
}
