package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.CouponEventTerms;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;

/**
 * 쿠폰 행사 만들기 요청을 검사해 {@link CouponEventTerms}로 바꾼다(CONTRACTS_API 3.2, FR-CPN-01).
 * 어긋나면 {@code 400 invalid}. 고른 혜택 유형에 쓰지 않는 칸은 버린다.
 */
final class CouponEventTermsValidator {

    static final int DEFAULT_VALID_DAYS = 7;
    static final int MIN_VALID_DAYS = 1;
    static final int MAX_VALID_DAYS = 30;
    static final int MIN_DISCOUNT_PERCENT = 1;
    static final int MAX_DISCOUNT_PERCENT = 50;
    static final int MAX_SERVICE_NAME_LENGTH = 50;
    static final int MAX_CONDITIONS_LENGTH = 200;

    private CouponEventTermsValidator() {
    }

    static CouponEventTerms toTerms(CouponEventCreateRequest request) {
        if (request == null) {
            throw invalid("요청 본문이 필요합니다.");
        }
        BenefitType type = benefitType(request.benefitType());
        int totalQuantity = atLeastOne(request.totalQuantity(), "총수량");
        int validDays = validDays(request.validDays());
        String conditions = conditions(request.conditions());

        return switch (type) {
            case FIXED_DISCOUNT -> new CouponEventTerms(type,
                    atLeastOne(request.discountAmount(), "할인 금액"),
                    null, null, null, null, validDays, conditions, totalQuantity);
            case PERCENT_DISCOUNT -> new CouponEventTerms(type, null,
                    discountPercent(request.discountPercent()),
                    optionalAtLeastOne(request.maxDiscountAmount(), "최대 할인액"),
                    null, null, validDays, conditions, totalQuantity);
            case SERVICE -> new CouponEventTerms(type, null, null, null,
                    serviceName(request.serviceName()),
                    atLeastOne(request.serviceValue(), "서비스 환산 금액"),
                    validDays, conditions, totalQuantity);
        };
    }

    private static BenefitType benefitType(String code) {
        if (code == null) {
            throw invalid("혜택 유형을 골라 주세요.");
        }
        return BenefitType.fromCode(code)
                .orElseThrow(() -> invalid("혜택 유형은 정액 할인 · 정률 할인 · 서비스 제공 중 하나입니다."));
    }

    private static int validDays(Integer days) {
        if (days == null) {
            return DEFAULT_VALID_DAYS;
        }
        if (days < MIN_VALID_DAYS || days > MAX_VALID_DAYS) {
            throw invalid("쿠폰 기간은 " + MIN_VALID_DAYS + " ~ " + MAX_VALID_DAYS + "일입니다.");
        }
        return days;
    }

    private static int discountPercent(Integer percent) {
        if (percent == null || percent < MIN_DISCOUNT_PERCENT || percent > MAX_DISCOUNT_PERCENT) {
            throw invalid("할인율은 " + MIN_DISCOUNT_PERCENT + " ~ " + MAX_DISCOUNT_PERCENT + "% 정수입니다.");
        }
        return percent;
    }

    private static String serviceName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || length(trimmed) > MAX_SERVICE_NAME_LENGTH) {
            throw invalid("서비스 내용은 1 ~ " + MAX_SERVICE_NAME_LENGTH + "자입니다.");
        }
        return trimmed;
    }

    private static String conditions(String conditions) {
        if (conditions == null || conditions.isBlank()) {
            return null;
        }
        String trimmed = conditions.strip();
        if (length(trimmed) > MAX_CONDITIONS_LENGTH) {
            throw invalid("조건은 " + MAX_CONDITIONS_LENGTH + "자 이하입니다.");
        }
        return trimmed;
    }

    private static int atLeastOne(Integer value, String name) {
        if (value == null || value < 1) {
            throw invalid(name + "은(는) 1 이상이어야 합니다.");
        }
        return value;
    }

    private static Integer optionalAtLeastOne(Integer value, String name) {
        return value == null ? null : atLeastOne(value, name);
    }

    /** DB의 VARCHAR 길이는 문자 수라 코드 포인트로 센다. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID, message);
    }
}
