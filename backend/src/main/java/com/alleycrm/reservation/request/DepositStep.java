package com.alleycrm.reservation.request;

import org.springframework.stereotype.Component;

/** 70 예약금 금액 (D10, FR-RSV-08): 가게의 인원당 예약금 × 인원. 0원 가게는 0. 납부는 저장 뒤 85단계가 기록한다. */
@Component
class DepositStep implements ReservationRequestStep {

    @Override
    public int order() {
        return DEPOSIT;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        ctx.setDepositAmount(Math.multiplyExact(ctx.getStore().depositPerPerson(), ctx.getPartySize()));
    }
}
