package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 추가 지표 · 쿠폰 지표(FR-ANL-08 · 09, C21). 범위는 세션 계정에서만 정한다(사장님 = 내 가게, 상인회 = 권역). */
@RestController
class ExtraMetricsController {

    private final ExtraMetricsService service;

    ExtraMetricsController(ExtraMetricsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/owner/extra-metrics")
    ExtraMetricsService.Metrics owner(@AuthenticationPrincipal AccountPrincipal me) {
        return service.forOwner(me);
    }

    @GetMapping("/api/v1/association/extra-metrics")
    ExtraMetricsService.Metrics association(@AuthenticationPrincipal AccountPrincipal me) {
        return service.forAssociation(me);
    }
}
