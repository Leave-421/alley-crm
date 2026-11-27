package com.alleycrm.customer;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 고객 목록(O-04) · 고객 카드(O-05) · 메모 (C9 · C10). 가게는 세션 계정에서만 정한다. */
@RestController
class CustomerCardController {

    private final CustomerCardService service;

    CustomerCardController(CustomerCardService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/owner/customers")
    Map<String, Object> list(@AuthenticationPrincipal AccountPrincipal me,
                             @RequestParam(required = false) String grade,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || (grade != null && !grade.matches(CustomerCardService.GRADES))) {
            throw new ApiException(ErrorCode.INVALID);
        }
        CustomerCardService.Page result = service.list(me, grade, page, size);
        return Map.of("items", result.items(), "page", page, "size", size, "totalElements", result.totalElements(),
                "totalPages", (result.totalElements() + size - 1) / size);
    }

    @GetMapping("/api/v1/owner/customers/{customerId}")
    CustomerCardService.Card card(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long customerId) {
        return service.card(me, customerId);
    }

    @PutMapping("/api/v1/owner/customers/{customerId}/memo")
    CustomerCardService.Card writeMemo(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long customerId,
                                       @RequestBody MemoRequest body) {
        return service.writeMemo(me, customerId, body.memo());
    }

    /** memo가 null · 빈 값이면 메모를 지운다 */
    record MemoRequest(String memo) {
    }
}
