package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 상인회 가게 성과 비교(A-02, C19). 권역은 세션 계정에서만 정한다. */
@RestController
class StorePerformanceController {

    private final StorePerformanceService service;

    StorePerformanceController(StorePerformanceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/association/store-performance")
    StorePerformanceService.Performance performance(@AuthenticationPrincipal AccountPrincipal me) {
        return service.performance(me);
    }
}
