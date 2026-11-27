import { api } from '@/api/client.js';

// 방문 신고(C23) — CONTRACTS_API.md 「방문 신고 (C23)」. 상인회 화면(A-11)이 쓰는 API.
// 상인회 · 사장님 응답에는 손님 이름 · 연락처 · 손님 ID가 없다(FR-STL-08 #5).

/** 신고 종류(CONTRACTS.md 「3」 visitReport.type) */
export const VISIT_REPORT_TYPE_LABELS = Object.freeze({ amount: '금액 신고', not_processed: '미처리 신고' });

/** 신고 상태(visitReport.status) */
export const VISIT_REPORT_STATUS_LABELS = Object.freeze({ received: '접수', owner_check_requested: '사장님 확인 요청', done: '처리 완료' });

/** 신고 결과(visitReport.result). 허위 신고 페널티는 없다 */
export const VISIT_REPORT_RESULT_LABELS = Object.freeze({ valid: '맞음', invalid: '아님' });

/** 내 권역 신고 목록(상인회). 오래된 신고부터, 공통 페이지. status 없으면 전부 */
export function getAssociationVisitReports({ status, page = 0, size = 20 } = {}) {
  return api.get('/api/v1/association/visit-reports', { query: { status, page, size } });
}

/** 사장님에게 확인 요청(접수 → 확인 요청). 사장님에게 알림이 간다. 응답은 VisitReport */
export function requestOwnerCheck(reportId) {
  return api.post(`/api/v1/association/visit-reports/${encodeURIComponent(reportId)}/owner-check`);
}

/** 결과 기록(확인 요청 → 처리 완료). result = 'valid' | 'invalid'. 금액 · 예약은 바꾸지 않는다. 응답은 VisitReport */
export function recordVisitReportResult(reportId, result) {
  return api.post(`/api/v1/association/visit-reports/${encodeURIComponent(reportId)}/result`, { result });
}
