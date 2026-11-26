package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.BenefitType;
import java.util.Arrays;
import java.util.List;

/** {@code GET /api/v1/coupon-events/benefit-types} 응답. */
public record BenefitTypesResponse(List<Item> items) {

    public record Item(String value, String label) {
    }

    public static BenefitTypesResponse all() {
        return new BenefitTypesResponse(Arrays.stream(BenefitType.values())
                .map(type -> new Item(type.code(), type.label()))
                .toList());
    }
}
