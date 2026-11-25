package com.alleycrm.coupon.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 혜택 유형 목록은 계약(CONTRACTS.md 「3」 benefitType)의 세 값이다(FR-CPN-01 #2). */
class BenefitTypesResponseTest {

    @Test
    void 정액_정률_서비스_세_가지() {
        assertThat(BenefitTypesResponse.all().items())
                .extracting(BenefitTypesResponse.Item::value, BenefitTypesResponse.Item::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("fixed_discount", "정액 할인"),
                        org.assertj.core.groups.Tuple.tuple("percent_discount", "정률 할인"),
                        org.assertj.core.groups.Tuple.tuple("service", "서비스 제공"));
    }
}
