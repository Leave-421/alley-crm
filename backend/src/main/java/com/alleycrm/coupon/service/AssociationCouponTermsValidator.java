package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.AssociationCouponTerms;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.domain.EligibleStoreScope;
import com.alleycrm.coupon.dto.AssociationCouponEventCreateRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 상인회 쿠폰 행사 만들기 요청을 검사해 {@link AssociationCouponTerms}로 바꾼다(CONTRACTS_API 3.8, FR-CPN-12).
 * 어긋나면 {@code 400 invalid}. 고른 혜택 유형에 쓰지 않는 칸은 버린다. 고른 가게가 그 권역 가게인지는 서비스가 DB로 본다.
 */
final class AssociationCouponTermsValidator {

    static final int MAX_TITLE_LENGTH = 50;
    static final int MIN_DISCOUNT_PERCENT = 1;
    static final int MAX_DISCOUNT_PERCENT = 50;
    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";

    private AssociationCouponTermsValidator() {
    }

    static AssociationCouponTerms toTerms(AssociationCouponEventCreateRequest request, Instant now) {
        if (request == null) {
            throw invalid("요청 본문이 필요합니다.");
        }
        String title = title(request.title());
        BenefitType type = benefitType(request.benefitType());
        int totalQuantity = atLeastOne(request.totalQuantity(), "수량");
        EligibleStoreScope scope = EligibleStoreScope.fromCode(request.eligibleStoreScope())
                .orElseThrow(() -> invalid("쓸 수 있는 가게는 all · selected 중 하나입니다."));
        Set<Long> storeIds = scope == EligibleStoreScope.SELECTED ? storeIds(request.storeIds()) : Set.of();

        Instant issueStart = instant(request.issueStartAt(), "받을 수 있는 기간 시작");
        Instant issueEnd = instant(request.issueEndAt(), "받을 수 있는 기간 끝");
        Instant validStart = instant(request.validStartAt(), "쓸 수 있는 기간 시작");
        Instant validEnd = instant(request.validEndAt(), "쓸 수 있는 기간 끝");
        if (!issueStart.isBefore(issueEnd) || !validStart.isBefore(validEnd)) {
            throw invalid("기간의 시작은 끝보다 앞이어야 합니다.");
        }
        if (issueEnd.isAfter(validEnd)) {
            throw invalid("받을 수 있는 기간은 쓸 수 있는 기간이 끝나기 전에 끝나야 합니다.");
        }
        if (!issueEnd.isAfter(now)) {
            throw invalid("받을 수 있는 기간이 이미 끝났습니다.");
        }

        return switch (type) {
            case FIXED_DISCOUNT -> new AssociationCouponTerms(title, type,
                    atLeastOne(request.discountAmount(), "할인 금액"), null, null,
                    totalQuantity, scope, storeIds, issueStart, issueEnd, validStart, validEnd);
            case PERCENT_DISCOUNT -> new AssociationCouponTerms(title, type, null,
                    discountPercent(request.discountPercent()),
                    atLeastOne(request.maxDiscountAmount(), "최대 할인액"), // 상인회 쿠폰은 필수(FR-CPN-12 #3)
                    totalQuantity, scope, storeIds, issueStart, issueEnd, validStart, validEnd);
            case SERVICE -> throw invalid("상인회 쿠폰은 정액 할인 · 정률 할인만 만들 수 있습니다.");
        };
    }

    private static String title(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_TITLE_LENGTH) {
            throw invalid("행사 이름은 1 ~ " + MAX_TITLE_LENGTH + "자입니다.");
        }
        return trimmed;
    }

    private static BenefitType benefitType(String code) {
        if (code == null) {
            throw invalid("혜택 유형을 골라 주세요.");
        }
        return BenefitType.fromCode(code)
                .orElseThrow(() -> invalid("상인회 쿠폰은 정액 할인 · 정률 할인만 만들 수 있습니다."));
    }

    private static int discountPercent(Integer percent) {
        if (percent == null || percent < MIN_DISCOUNT_PERCENT || percent > MAX_DISCOUNT_PERCENT) {
            throw invalid("할인율은 " + MIN_DISCOUNT_PERCENT + " ~ " + MAX_DISCOUNT_PERCENT + "% 정수입니다.");
        }
        return percent;
    }

    private static Set<Long> storeIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw invalid("쓸 수 있는 가게를 하나 이상 골라 주세요.");
        }
        Set<Long> ids = new HashSet<>();
        for (String id : raw) {
            if (id == null || !id.matches(ID_PATTERN) || !ids.add(Long.parseLong(id))) {
                throw invalid("가게 ID를 확인해 주세요.");
            }
        }
        return ids;
    }

    /** ISO 8601(예: 2026-12-01T00:00:00Z). DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. */
    private static Instant instant(String value, String name) {
        if (value == null) {
            throw invalid(name + "이(가) 필요합니다.");
        }
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw invalid(name + " 형식을 확인해 주세요.");
        }
    }

    private static int atLeastOne(Integer value, String name) {
        if (value == null || value < 1) {
            throw invalid(name + "은(는) 1 이상이어야 합니다.");
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID, message);
    }
}
