package com.alleycrm.coupon.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB에는 계약의 소문자 값({@code all} · {@code selected})으로 저장한다. */
@Converter(autoApply = true)
public class EligibleStoreScopeConverter implements AttributeConverter<EligibleStoreScope, String> {

    @Override
    public String convertToDatabaseColumn(EligibleStoreScope scope) {
        return scope == null ? null : scope.code();
    }

    @Override
    public EligibleStoreScope convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return EligibleStoreScope.fromCode(code)
                .orElseThrow(() -> new IllegalStateException("알 수 없는 eligible_store_scope: " + code));
    }
}
