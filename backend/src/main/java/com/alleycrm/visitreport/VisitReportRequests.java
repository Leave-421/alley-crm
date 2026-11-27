package com.alleycrm.visitreport;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 신고 API 요청 본문들 */
final class VisitReportRequests {

    /** 회원 신고. claimedAmount는 금액 신고에서만 쓰고 선택이다 */
    record Create(
            @NotNull @Pattern(regexp = VisitReport.TYPES) String type,
            @Min(0) @Max(10_000_000) Integer claimedAmount) {
    }

    /** 상인회 결과 기록 */
    record Result(@NotNull @Pattern(regexp = VisitReport.RESULTS) String result) {
    }

    /** 사장님 답변 */
    record Reply(@NotBlank @Size(max = 200) String reply) {
    }

    private VisitReportRequests() {
    }
}
