package com.alleycrm.coupon.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;

/** 회원에게 보이는 상인회 쿠폰 카드 상태. 값은 docs/CONTRACTS.md 「3」 {@code associationCouponOffer.status}와 같다. */
public enum AssociationCouponOfferStatus {

    AVAILABLE("available"),
    UPCOMING("upcoming"),
    RECEIVED("received"),
    /** 마감: 다 나감 · 중단 · 받는 기간 끝. */
    CLOSED("closed");

    private final String code;

    AssociationCouponOfferStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** 3.9 순서: 받음 → 마감 → 받기 전 → 받을 수 있음. */
    public static AssociationCouponOfferStatus of(AssociationCouponEvent event, boolean received, Instant now) {
        if (received) {
            return RECEIVED;
        }
        if (!event.isActive() || event.remainingQuantity() == 0 || !now.isBefore(event.getIssueEndAt())) {
            return CLOSED;
        }
        if (now.isBefore(event.getIssueStartAt())) {
            return UPCOMING;
        }
        return AVAILABLE;
    }
}
