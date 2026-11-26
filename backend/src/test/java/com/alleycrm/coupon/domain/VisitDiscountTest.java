package com.alleycrm.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 방문 할인 계산(05-policy 「혜택과 비용」, FR-CPN-07 #3 · #5 · #6)과 쿠폰 기간(FR-CPN-05 #5). */
class VisitDiscountTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 1, 9, 0);

    @Test
    void 정액_할인은_방문_금액까지만_깎고_남는_할인은_사라진다_FR_CPN_07_3() {
        assertThat(VisitDiscount.of(fixed(5000), 4000)).isEqualTo(new VisitDiscount(4000, 4000));
        assertThat(VisitDiscount.of(fixed(5000), 30000)).isEqualTo(new VisitDiscount(5000, 5000));
        assertThat(VisitDiscount.of(fixed(5000), 0)).isEqualTo(new VisitDiscount(0, 0));
    }

    @Test
    void 정률_할인은_최대_할인액까지만_FR_CPN_07_5() {
        assertThat(VisitDiscount.of(percent(10, 5000), 80000)).isEqualTo(new VisitDiscount(5000, 5000));
        assertThat(VisitDiscount.of(percent(10, 5000), 30000)).isEqualTo(new VisitDiscount(3000, 3000));
    }

    @Test
    void 정률_할인은_1원_미만을_버린다_FR_CPN_07_6() {
        assertThat(VisitDiscount.of(percent(15, null), 33333)).isEqualTo(new VisitDiscount(4999, 4999));
    }

    @Test
    void 서비스_제공은_할인_없이_환산_금액을_비용으로() {
        CouponPolicy service = CouponPolicy.confirm(1L, new CouponEventTerms(
                BenefitType.SERVICE, null, null, null, "아메리카노", 4500, 7, null, 10), NOW);

        assertThat(VisitDiscount.of(service, 20000)).isEqualTo(new VisitDiscount(0, 4500));
    }

    @Test
    void 쿠폰_기간을_3일로_정하면_만료_시각은_다운로드_더하기_3_곱하기_24시간_FR_CPN_05_5() {
        CouponPolicy threeDays = CouponPolicy.confirm(1L, new CouponEventTerms(
                BenefitType.FIXED_DISCOUNT, 3000, null, null, null, null, 3, null, 10), NOW);

        Coupon coupon = Coupon.download(9L, threeDays, 2L, 3L, NOW);

        assertThat(coupon.getExpiresAt()).isEqualTo(NOW.plusHours(72));
    }

    private static CouponPolicy fixed(int amount) {
        return CouponPolicy.confirm(1L, new CouponEventTerms(
                BenefitType.FIXED_DISCOUNT, amount, null, null, null, null, 7, null, 10), NOW);
    }

    private static CouponPolicy percent(int rate, Integer max) {
        return CouponPolicy.confirm(1L, new CouponEventTerms(
                BenefitType.PERCENT_DISCOUNT, null, rate, max, null, null, 7, null, 10), NOW);
    }
}
