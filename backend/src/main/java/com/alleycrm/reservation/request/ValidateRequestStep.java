package com.alleycrm.reservation.request;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.reservation.request.ReservationRequestBody.Guest;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 10 입력 검증 (D2): 필수 값 · 인원 · 공유 동의 · 비회원 정보. DB를 읽지 않는다. */
@Component
class ValidateRequestStep implements ReservationRequestStep {

    private static final Set<String> GENDERS = Set.of("female", "male");
    private static final Set<String> AGE_GROUPS = Set.of("age_20_or_under", "age_30s", "age_40s", "age_50_or_over");

    @Override
    public int order() {
        return VALIDATE;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        ReservationRequestBody b = ctx.getBody();
        if (b == null || b.startAt() == null || b.partySize() == null) {
            throw invalid();
        }
        // 공유 동의가 없으면 신청할 수 없다(FR-RSV-01 #1, FR-CUS-01)
        if (!Boolean.TRUE.equals(b.shareConsent())) {
            throw invalid();
        }
        if (b.partySize() < 1) {
            throw invalid();
        }
        if (b.requestNote() != null && b.requestNote().length() > 200) {
            throw invalid();
        }
        // 비회원은 이름 · 전화번호 필수(FR-RSV-01 #2). 회원이 보낸 guest는 무시한다
        if (!ctx.loggedIn()) {
            checkGuest(b.guest());
        }
        ctx.setStoreId(requiredId(b.storeId()));
        ctx.setStartAt(b.startAt());
        ctx.setPartySize(b.partySize());
        ctx.setCouponId(optionalId(b.couponId()));
        ctx.setCourseRootId(optionalId(b.courseRootId()));
        ctx.setCourseProposalId(optionalId(b.courseProposalId()));
    }

    private static void checkGuest(Guest g) {
        if (g == null || g.name() == null || g.phone() == null) {
            throw invalid();
        }
        String name = g.name().strip();
        if (name.isEmpty() || name.length() > 20) {
            throw invalid();
        }
        if (!g.phone().matches("\\d{10,11}")) {
            throw invalid();
        }
        if (g.gender() != null && !GENDERS.contains(g.gender())) {
            throw invalid();
        }
        if (g.ageGroup() != null && !AGE_GROUPS.contains(g.ageGroup())) {
            throw invalid();
        }
    }

    private static Long requiredId(String value) {
        Long id = optionalId(value);
        if (id == null) {
            throw invalid();
        }
        return id;
    }

    private static Long optionalId(String value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw invalid();
            }
            return id;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID);
    }
}
