package com.alleycrm.visitreport;

/** 상인회가 신고 확인을 사장님에게 요청함. 커밋 뒤 C14가 그 가게 사장님에게 visit_report_check_requested 알림을 남긴다. */
public record VisitReportOwnerCheckRequestedEvent(long visitReportId, long reservationId, long storeId) {
}
