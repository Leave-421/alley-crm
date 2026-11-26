package com.alleycrm.recommend.recommendation;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalRequest;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalsRecorded;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalsResponse;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정식 코스 제안(E7, CONTRACTS_API 3.1). 그 기준 예약의 손님 본인만 쓴다. 검사 순서: 로그인(401) → 역할(403) → 예약 주인(404).
 * 비회원 제한 세션(C13)은 아직 없어 지금은 회원만 통과한다.
 */
@RestController
class CourseProposalController {

    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";
    private static final int MAX_STORES = 3;

    private final CourseProposalService service;
    private final CustomerService customers;

    CourseProposalController(CourseProposalService service, CustomerService customers) {
        this.service = service;
        this.customers = customers;
    }

    @GetMapping("/api/v1/course-proposals")
    ResponseEntity<CourseProposalsResponse> proposals(@AuthenticationPrincipal AccountPrincipal me,
            @RequestParam(required = false) String baseReservationId, @RequestParam(required = false) String page) {
        long customerId = customer(me);
        long reservationId = requiredId(baseReservationId);
        int pageNo = pageOf(page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.proposals(reservationId, pageNo, customerId));
    }

    @PostMapping("/api/v1/course-proposals")
    CourseProposalsRecorded record(@AuthenticationPrincipal AccountPrincipal me,
            @RequestBody(required = false) CourseProposalRequest body) {
        long customerId = customer(me);
        if (body == null) {
            throw invalid();
        }
        long reservationId = requiredId(body.baseReservationId());
        List<String> raw = body.storeIds();
        if (raw == null || raw.isEmpty() || raw.size() > MAX_STORES || new HashSet<>(raw).size() != raw.size()) {
            throw invalid();
        }
        List<Long> storeIds = raw.stream().map(CourseProposalController::requiredId).toList();
        return service.record(reservationId, storeIds, customerId);
    }

    /** 로그인한 회원 손님의 손님 ID. 비로그인 401, 사장님 · 상인회 403. */
    private long customer(AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        me.requireRole(AccountRole.CUSTOMER);
        return customers.identifyMember(me.id());
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
