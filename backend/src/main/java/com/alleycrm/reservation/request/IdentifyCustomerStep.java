package com.alleycrm.reservation.request;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.account.AccountService;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.reservation.request.ReservationRequestBody.Guest;
import org.springframework.stereotype.Component;

/**
 * 20 손님 식별 (C4, FR-CUS-02): 로그인한 회원은 계정의 손님, 비회원은 전화번호로 손님을 찾거나 만든다.
 * 사장님 · 상인회 계정은 예약을 신청할 수 없다(forbidden_role). 익명 방문자 연결은 E3에서 더한다.
 */
@Component
class IdentifyCustomerStep implements ReservationRequestStep {

    private final AccountService accountService;
    private final CustomerService customerService;

    IdentifyCustomerStep(AccountService accountService, CustomerService customerService) {
        this.accountService = accountService;
        this.customerService = customerService;
    }

    @Override
    public int order() {
        return IDENTIFY_CUSTOMER;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        if (ctx.loggedIn()) {
            AccountPrincipal me = accountService.getByLoginId(ctx.getLoginName());
            me.requireRole(AccountRole.CUSTOMER);
            ctx.setCustomerId(customerService.identifyMember(me.id()));
            return;
        }
        Guest g = ctx.getBody().guest(); // 단계 10이 비회원 guest의 필수 값 · 형식을 확인했다
        ctx.setCustomerId(customerService.identifyGuest(g.name().strip(), g.phone(), g.gender(), g.ageGroup()));
    }
}
