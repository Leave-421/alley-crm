package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 세그먼트 분석(O-06, C16). 가게는 세션 계정에서만 정한다. */
@RestController
class SegmentController {

    private final SegmentService service;

    SegmentController(SegmentService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/owner/segments")
    SegmentService.Segments segments(@AuthenticationPrincipal AccountPrincipal me) {
        return service.segments(me);
    }
}
