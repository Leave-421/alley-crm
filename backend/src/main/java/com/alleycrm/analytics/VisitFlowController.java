package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 상인회 방문 흐름(A-03, C20). 권역은 세션 계정에서만 정한다. */
@RestController
class VisitFlowController {

    private final VisitFlowService service;

    VisitFlowController(VisitFlowService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/association/visit-flows")
    VisitFlowService.Flows flows(@AuthenticationPrincipal AccountPrincipal me) {
        return service.flows(me);
    }
}
