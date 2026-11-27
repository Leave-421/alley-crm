package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 손님 흐름(O-07, C17). 가게는 세션 계정에서만 정한다. */
@RestController
class CustomerFlowController {

    private final CustomerFlowService service;

    CustomerFlowController(CustomerFlowService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/owner/customer-flows")
    CustomerFlowService.Flow flow(@AuthenticationPrincipal AccountPrincipal me) {
        return service.flow(me);
    }
}
