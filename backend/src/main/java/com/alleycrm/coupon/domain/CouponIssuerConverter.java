package com.alleycrm.coupon.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB에는 계약의 소문자 값({@code store} · {@code association})으로 저장한다. */
@Converter(autoApply = true)
public class CouponIssuerConverter implements AttributeConverter<CouponIssuer, String> {

    @Override
    public String convertToDatabaseColumn(CouponIssuer issuer) {
        return issuer == null ? null : issuer.code();
    }

    @Override
    public CouponIssuer convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return CouponIssuer.fromCode(code)
                .orElseThrow(() -> new IllegalStateException("알 수 없는 coupon.issuer_kind: " + code));
    }
}
