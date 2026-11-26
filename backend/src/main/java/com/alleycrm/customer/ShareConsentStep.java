package com.alleycrm.customer;

import com.alleycrm.reservation.request.ReservationRequestContext;
import com.alleycrm.reservation.request.ReservationRequestStep;
import org.springframework.stereotype.Component;

/**
 * 예약 신청 단계 82 공유 동의 기록(C8, CONTRACTS_API 2 「예약 신청 처리 단계」). 동의 여부는 입력 검증(10)이 이미 확인했다.
 * 기록이 예약 ID를 가리켜서 저장(80) 뒤에 둔다. 같은 트랜잭션이라 기록이 실패하면 예약도 저장되지 않는다.
 */
@Component
class ShareConsentStep implements ReservationRequestStep {

    /** 저장(80) 다음. D2의 순서 상수 CONSENT(50)는 쓰지 않고 계약 표에 적었다. */
    static final int SHARE_CONSENT = 82;

    private final ShareConsentService consents;

    ShareConsentStep(ShareConsentService consents) {
        this.consents = consents;
    }

    @Override
    public int order() {
        return SHARE_CONSENT;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        consents.record(ctx.getReservation().getId(), ctx.getStore().regionId(), ctx.getRequestedAt());
    }
}
