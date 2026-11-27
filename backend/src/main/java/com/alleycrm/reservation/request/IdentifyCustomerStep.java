package com.alleycrm.reservation.request;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.account.AccountService;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.recommend.visitor.VisitorService;
import com.alleycrm.reservation.request.ReservationRequestBody.Guest;
import org.springframework.stereotype.Component;

/**
 * 20 손님 식별 (C4, FR-CUS-02): 로그인한 회원은 계정의 손님, 비회원은 전화번호로 손님을 찾거나 만든다.
 * 사장님 · 상인회 계정은 예약을 신청할 수 없다(forbidden_role). 손님을 정한 뒤 이 브라우저의 방문자를 그 손님에 잇는다
 * (3.3 「방문자를 손님에 잇기」, E3 VisitorService). 신청이 롤백되면 연결도 남지 않는다(같은 트랜잭션).
 */
@Component
class IdentifyCustomerStep implements ReservationRequestStep {

    private final AccountService accountService;
    private final CustomerService customerService;
    private final VisitorService visitors;

    IdentifyCustomerStep(AccountService accountService, CustomerService customerService, VisitorService visitors) {
        this.accountService = accountService;
        this.customerService = customerService;
        this.visitors = visitors;
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
        } else {
            Guest g = ctx.getBody().guest(); // 단계 10이 비회원 guest의 필수 값 · 형식을 확인했다
            ctx.setCustomerId(customerService.identifyGuest(g.name().strip(), g.phone(), g.gender(), g.ageGroup()));
        }
        visitors.linkCurrentBrowser(ctx.getCustomerId());
    }
}
