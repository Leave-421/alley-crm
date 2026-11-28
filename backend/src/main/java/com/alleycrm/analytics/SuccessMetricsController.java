package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상인회 성공 지표(A-01, C18). 권역은 세션 계정에서만 정한다. */
@RestController
class SuccessMetricsController {

    private final SuccessMetricsService service;

    SuccessMetricsController(SuccessMetricsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/association/success-metrics")
    SuccessMetricsService.Metrics metrics(@RequestParam(required = false) String month,
            @AuthenticationPrincipal AccountPrincipal me) {
        return service.metrics(me, month);
    }
}
