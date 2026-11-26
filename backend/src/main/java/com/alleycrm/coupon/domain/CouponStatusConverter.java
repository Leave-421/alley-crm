package com.alleycrm.coupon.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB에는 계약의 소문자 값({@code downloaded} 등)으로 저장한다. */
@Converter(autoApply = true)
public class CouponStatusConverter implements AttributeConverter<CouponStatus, String> {

    @Override
    public String convertToDatabaseColumn(CouponStatus status) {
        return status == null ? null : status.code();
    }

    @Override
    public CouponStatus convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return CouponStatus.fromCode(code)
                .orElseThrow(() -> new IllegalStateException("알 수 없는 coupon.status: " + code));
    }
}
