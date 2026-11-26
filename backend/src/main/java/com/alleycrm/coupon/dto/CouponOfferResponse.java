package com.alleycrm.coupon.dto;

import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.DownloadStatus;
import java.time.Instant;
import java.util.List;

/**
 * 쿠폰 상세(CONTRACTS_API 3.4 {@code GET /api/v1/coupon-offers/{storeId}}). ID는 문자열, 시각은 UTC.
 * 서비스 환산 금액 · 남은 수량은 손님에게 주지 않는다.
 */
public record CouponOfferResponse(
        String storeId,
        OfferEvent event,
        DownloadStatus downloadStatus,
        Instant nextDownloadableAt,
        List<MyCoupon> myCoupons) {

    public static CouponOfferResponse of(long storeId, CouponPolicy event, DownloadStatus downloadStatus,
            Instant nextDownloadableAt, List<Coupon> myCoupons) {
        return new CouponOfferResponse(
                String.valueOf(storeId),
                event == null ? null : OfferEvent.from(event),
                downloadStatus,
                nextDownloadableAt,
                myCoupons.stream().map(MyCoupon::from).toList());
    }

    /** 진행 중인 행사의 혜택. 칸의 뜻은 3.2 CouponEvent와 같다. */
    public record OfferEvent(
            String eventId,
            BenefitType benefitType,
            Integer discountAmount,
            Integer discountPercent,
            Integer maxDiscountAmount,
            String serviceName,
            int validDays,
            String conditions) {

        static OfferEvent from(CouponPolicy event) {
            return new OfferEvent(
                    String.valueOf(event.getId()),
                    event.getBenefitType(),
                    event.getDiscountAmount(),
                    event.getDiscountPercent(),
                    event.getMaxDiscountAmount(),
                    event.getServiceName(),
                    event.getValidDays(),
                    event.getConditions());
        }
    }

    public record MyCoupon(String couponId, String eventId, CouponStatus status, Instant expiresAt) {

        static MyCoupon from(Coupon coupon) {
            return new MyCoupon(
                    String.valueOf(coupon.getId()),
                    String.valueOf(coupon.getCouponPolicyId()),
                    coupon.getStatus(),
                    coupon.getExpiresAt());
        }
    }

}
