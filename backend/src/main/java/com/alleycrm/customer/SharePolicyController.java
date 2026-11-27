package com.alleycrm.customer;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.customer.ShareConsentService.PolicyItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 상인회 정보 공유 설정(A-08, FR-CUS-06, C11). 권역은 세션 계정에서만 정한다. */
@RestController
class SharePolicyController {

    private final ShareConsentService service;

    SharePolicyController(ShareConsentService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/association/share-policy")
    Map<String, List<PolicyItem>> policy(@AuthenticationPrincipal AccountPrincipal me) {
        return Map.of("items", service.policy(me));
    }

    @PutMapping("/api/v1/association/share-policy/{item}")
    Map<String, List<PolicyItem>> change(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String item,
                                         @Valid @RequestBody ChangeRequest body) {
        return Map.of("items", service.change(me, item, body.enabled()));
    }

    record ChangeRequest(@NotNull Boolean enabled) {
    }
}
