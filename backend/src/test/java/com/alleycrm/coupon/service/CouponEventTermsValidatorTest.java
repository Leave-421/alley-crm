package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.CouponEventTerms;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 쿠폰 행사 만들기 검사(FR-CPN-01, CONTRACTS_API 3.2). DB 없이 돈다. */
class CouponEventTermsValidatorTest {

    @Test
    void 정액_할인은_할인_금액만_남기고_다른_유형_칸은_버린다() {
        CouponEventTerms terms = CouponEventTermsValidator.toTerms(new CouponEventCreateRequest(
                "fixed_discount", 3000, 10, 5000, "아메리카노", 4000, 14, " 2인 이상 ", 100));

        assertThat(terms.benefitType()).isEqualTo(BenefitType.FIXED_DISCOUNT);
        assertThat(terms.discountAmount()).isEqualTo(3000);
        assertThat(terms.discountPercent()).isNull();
        assertThat(terms.maxDiscountAmount()).isNull();
        assertThat(terms.serviceName()).isNull();
        assertThat(terms.serviceValue()).isNull();
        assertThat(terms.validDays()).isEqualTo(14);
        assertThat(terms.conditions()).isEqualTo("2인 이상");
        assertThat(terms.totalQuantity()).isEqualTo(100);
    }

    @Test
    void 정률_할인은_할인율과_선택_최대_할인액() {
        CouponEventTerms capped = CouponEventTermsValidator.toTerms(percent(10, 5000));
        CouponEventTerms uncapped = CouponEventTermsValidator.toTerms(percent(50, null));

        assertThat(capped.benefitType()).isEqualTo(BenefitType.PERCENT_DISCOUNT);
        assertThat(capped.discountPercent()).isEqualTo(10);
        assertThat(capped.maxDiscountAmount()).isEqualTo(5000);
        assertThat(capped.discountAmount()).isNull();
        assertThat(uncapped.discountPercent()).isEqualTo(50);
        assertThat(uncapped.maxDiscountAmount()).isNull();
    }

    @Test
    void 서비스_제공은_내용과_환산_금액() {
        CouponEventTerms terms = CouponEventTermsValidator.toTerms(new CouponEventCreateRequest(
                "service", 3000, null, null, " 아메리카노 1잔 ", 4000, null, null, 50));

        assertThat(terms.benefitType()).isEqualTo(BenefitType.SERVICE);
        assertThat(terms.serviceName()).isEqualTo("아메리카노 1잔");
        assertThat(terms.serviceValue()).isEqualTo(4000);
        assertThat(terms.discountAmount()).isNull();
    }

    @Test
    void 쿠폰_기간을_빼면_7일() {
        assertThat(CouponEventTermsValidator.toTerms(fixed(null)).validDays()).isEqualTo(7);
    }

    @Test
    void 쿠폰_기간은_1에서_30일() {
        assertThat(CouponEventTermsValidator.toTerms(fixed(1)).validDays()).isEqualTo(1);
        assertThat(CouponEventTermsValidator.toTerms(fixed(30)).validDays()).isEqualTo(30);
        assertInvalid(fixed(0));
        assertInvalid(fixed(31));
    }

    @Test
    void 빈_조건은_null() {
        CouponEventTerms terms = CouponEventTermsValidator.toTerms(new CouponEventCreateRequest(
                "fixed_discount", 1000, null, null, null, null, null, "   ", 10));

        assertThat(terms.conditions()).isNull();
    }

    @Test
    void 조건은_200자까지() {
        assertThat(CouponEventTermsValidator.toTerms(withConditions("가".repeat(200))).conditions()).hasSize(200);
        assertInvalid(withConditions("가".repeat(201)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate_discount", "FIXED_DISCOUNT", ""})
    void 정해진_세_유형이_아니면_거절(String type) {
        assertInvalid(new CouponEventCreateRequest(type, 1000, null, null, null, null, null, null, 10));
    }

    @Test
    void 필수_칸이_없으면_거절() {
        assertInvalid(null);
        assertInvalid(new CouponEventCreateRequest(null, 1000, null, null, null, null, null, null, 10));
        assertInvalid(new CouponEventCreateRequest("fixed_discount", 1000, null, null, null, null, null, null, null));
        assertInvalid(new CouponEventCreateRequest("fixed_discount", 1000, null, null, null, null, null, null, 0));
    }

    @Test
    void 정액_할인_금액이_없거나_0이면_거절() {
        assertInvalid(new CouponEventCreateRequest("fixed_discount", null, null, null, null, null, null, null, 10));
        assertInvalid(new CouponEventCreateRequest("fixed_discount", 0, null, null, null, null, null, null, 10));
    }

    @Test
    void 정률_할인율은_1에서_50_최대_할인액은_1_이상() {
        assertInvalid(percent(null, null));
        assertInvalid(percent(0, null));
        assertInvalid(percent(51, null));
        assertInvalid(percent(10, 0));
    }

    @Test
    void 서비스는_환산_금액과_내용이_없으면_거절() {
        assertInvalid(new CouponEventCreateRequest("service", null, null, null, "아메리카노", null, null, null, 10));
        assertInvalid(new CouponEventCreateRequest("service", null, null, null, "아메리카노", 0, null, null, 10));
        assertInvalid(new CouponEventCreateRequest("service", null, null, null, "  ", 4000, null, null, 10));
        assertInvalid(new CouponEventCreateRequest("service", null, null, null, "가".repeat(51), 4000, null, null, 10));
    }

    private static CouponEventCreateRequest fixed(Integer validDays) {
        return new CouponEventCreateRequest("fixed_discount", 1000, null, null, null, null, validDays, null, 10);
    }

    private static CouponEventCreateRequest percent(Integer percent, Integer max) {
        return new CouponEventCreateRequest("percent_discount", null, percent, max, null, null, null, null, 10);
    }

    private static CouponEventCreateRequest withConditions(String conditions) {
        return new CouponEventCreateRequest("fixed_discount", 1000, null, null, null, null, null, conditions, 10);
    }

    private static void assertInvalid(CouponEventCreateRequest request) {
        assertThatThrownBy(() -> CouponEventTermsValidator.toTerms(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
    }
}
