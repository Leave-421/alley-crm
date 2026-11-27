package com.alleycrm.recommend.record;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/recommendation-feedback} — 추천 거절 · 리롤 기록(E8, CONTRACTS_API 3.11, FR-CRS-06). 사유는 받지 않고
 * 순위에 쓰지 않는다. 로그인 없이 부르는 경로이고, 보안 설정에 여는 것은 C가 한다(3.11 N-2).
 */
@RestController
class RecommendationFeedbackController {

    static final Set<String> TYPES = Set.of("reject", "reroll");
    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";
    private static final int MAX_EXPOSURES = 3;

    record FeedbackRequest(String type, List<String> exposureIds) {
    }

    private final ExplorationRecordService service;
    private final CustomerService customers;

    RecommendationFeedbackController(ExplorationRecordService service, CustomerService customers) {
        this.service = service;
        this.customers = customers;
    }

    @PostMapping("/api/v1/recommendation-feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@AuthenticationPrincipal AccountPrincipal me, @RequestBody(required = false) FeedbackRequest body) {
        if (body == null || body.type() == null || !TYPES.contains(body.type())) {
            throw invalid();
        }
        List<String> raw = body.exposureIds();
        if (raw == null || raw.isEmpty() || raw.size() > MAX_EXPOSURES || new HashSet<>(raw).size() != raw.size()
                || !raw.stream().allMatch(id -> id != null && id.matches(ID_PATTERN))) {
            throw invalid();
        }
        // 회원이면 이 회원에 이어진 방문자의 노출도 본인 것. 익명 · 사장님 · 상인회는 이 브라우저의 방문자만
        Long customerId = me != null && AccountRole.CUSTOMER.equals(me.role()) ? customers.identifyMember(me.id()) : null;
        service.recordFeedback(body.type(), raw.stream().map(Long::parseLong).toList(), customerId);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID);
    }
}
