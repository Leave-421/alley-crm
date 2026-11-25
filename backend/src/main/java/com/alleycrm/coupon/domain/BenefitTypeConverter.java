package com.alleycrm.coupon.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB에는 계약의 소문자 값({@code fixed_discount} 등)으로 저장한다. */
@Converter(autoApply = true)
public class BenefitTypeConverter implements AttributeConverter<BenefitType, String> {

    @Override
    public String convertToDatabaseColumn(BenefitType type) {
        return type == null ? null : type.code();
    }

    @Override
    public BenefitType convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return BenefitType.fromCode(code)
                .orElseThrow(() -> new IllegalStateException("알 수 없는 benefit_type: " + code));
    }
}
