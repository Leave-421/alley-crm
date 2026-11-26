package com.alleycrm.recommend.recommendation;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.recommend.recommendation.RecommendationDtos.ExposureRequest;
import com.alleycrm.recommend.recommendation.RecommendationDtos.ExposuresResponse;
import com.alleycrm.recommend.recommendation.RecommendationDtos.RecommendationsResponse;
import com.alleycrm.recommend.recommendation.RecommendationService.Requester;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지도 추천(E7, CONTRACTS_API 3.1). 누구나 쓴다 — 로그인 없이 여는 보안 설정은 C(#24, 3.1 R-10)가 더한다.
 * 손님 식별 · 성별 · 연령대는 요청 인자가 아니라 세션으로 정한다.
 */
@RestController
class RecommendationController {

    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";
    private static final int MAX_STORES = 3;

    private final RecommendationService service;
    private final CustomerService customers;

    RecommendationController(RecommendationService service, CustomerService customers) {
        this.service = service;
        this.customers = customers;
    }

    @GetMapping("/api/v1/recommendations")
    ResponseEntity<RecommendationsResponse> recommend(@AuthenticationPrincipal AccountPrincipal me,
            @RequestParam(required = false) String baseStoreId, @RequestParam(required = false) String page) {
        long base = requiredId(baseStoreId);
        int pageNo = pageOf(page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recommend(base, pageNo, requester(me)));
    }

    @PostMapping("/api/v1/recommendation-exposures")
    ExposuresResponse recordExposures(@AuthenticationPrincipal AccountPrincipal me,
            @RequestBody(required = false) ExposureRequest body) {
        if (body == null) {
            throw invalid();
        }
        UUID displayId;
        try {
            displayId = UUID.fromString(body.displayId() == null ? "" : body.displayId());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        long base = requiredId(body.baseStoreId());
        int pageNo = body.page() == null ? 0 : body.page();
        if (pageNo < 0) {
            throw invalid();
        }
        List<String> raw = body.storeIds();
        if (raw == null || raw.isEmpty() || raw.size() > MAX_STORES || new HashSet<>(raw).size() != raw.size()) {
            throw invalid();
        }
        List<Long> storeIds = raw.stream().map(RecommendationController::requiredId).toList();
        return service.recordExposures(displayId, base, pageNo, storeIds, requester(me));
    }

    /** 회원이면 그 손님, 익명 · 사장님 · 상인회는 식별 안 됨(3.1 「손님 식별에 따른 차이」). */
    private Requester requester(AccountPrincipal me) {
        if (me != null && AccountRole.CUSTOMER.equals(me.role())) {
            return new Requester(customers.identifyMember(me.id()));
        }
        return Requester.UNIDENTIFIED;
    }

    private static long requiredId(String value) {
        if (value == null || !value.matches(ID_PATTERN)) {
            throw invalid();
        }
        return Long.parseLong(value);
    }

    private static int pageOf(String value) {
        if (value == null) {
            return 0;
        }
        if (!value.matches("[0-9]{1,6}")) {
            throw invalid();
        }
        return Integer.parseInt(value);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID);
    }
}
