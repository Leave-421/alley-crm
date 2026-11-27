package com.alleycrm.visitreport;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 방문 신고 API(C23, FR-STL-08): 회원 신고 · 상인회 처리 · 사장님 답변. */
@RestController
class VisitReportController {

    private final VisitReportService service;

    VisitReportController(VisitReportService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/reservations/{reservationId}/visit-reports")
    @ResponseStatus(HttpStatus.CREATED)
    VisitReportResponses.Mine create(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long reservationId,
                                     @Valid @RequestBody VisitReportRequests.Create body) {
        return VisitReportResponses.Mine.of(service.create(me, reservationId, body.type(), body.claimedAmount()));
    }

    @GetMapping("/api/v1/visit-reports/mine")
    Map<String, Object> mine(@AuthenticationPrincipal AccountPrincipal me) {
        List<VisitReportResponses.Mine> items = service.mine(me).stream().map(VisitReportResponses.Mine::of).toList();
        return Map.of("items", items);
    }

    @GetMapping("/api/v1/association/visit-reports")
    Map<String, Object> associationList(@AuthenticationPrincipal AccountPrincipal me,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        checkPage(page, size);
        if (status != null && !status.matches(VisitReport.STATUSES)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return pageBody(service.associationPage(me, status, page, size), page, size);
    }

    @PostMapping("/api/v1/association/visit-reports/{reportId}/owner-check")
    VisitReportResponses.Detail requestOwnerCheck(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long reportId) {
        service.requestOwnerCheck(me, reportId);
        return service.associationDetail(me, reportId);
    }

    @PostMapping("/api/v1/association/visit-reports/{reportId}/result")
    VisitReportResponses.Detail recordResult(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long reportId,
                                             @Valid @RequestBody VisitReportRequests.Result body) {
        service.recordResult(me, reportId, body.result());
        return service.associationDetail(me, reportId);
    }

    @GetMapping("/api/v1/owner/visit-reports")
    Map<String, Object> ownerList(@AuthenticationPrincipal AccountPrincipal me,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        checkPage(page, size);
        return pageBody(service.ownerPage(me, page, size), page, size);
    }

    @PostMapping("/api/v1/owner/visit-reports/{reportId}/reply")
    VisitReportResponses.Detail reply(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long reportId,
                                      @Valid @RequestBody VisitReportRequests.Reply body) {
        service.reply(me, reportId, body.reply());
        return service.ownerDetail(me, reportId);
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }

    private static Map<String, Object> pageBody(VisitReportService.Page result, int page, int size) {
        return Map.of("items", result.items(), "page", page, "size", size, "totalElements", result.totalElements(),
                "totalPages", (result.totalElements() + size - 1) / size);
    }
}
