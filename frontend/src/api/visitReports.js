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

// ── 사장님 쪽(B29): 확인 요청 받은 신고 · 답변(C23), '맞음' 뒤 금액 정정(D22) · 늦은 방문 처리(D23) ──

/** 내 가게 신고(사장님). 확인 요청을 받은 신고(확인 요청 · 처리 완료)만, 최근 것부터, 공통 페이지 */
export function getOwnerVisitReports({ page = 0, size = 20 } = {}) {
  return api.get('/api/v1/owner/visit-reports', { query: { page, size } });
}

/** 사장님 답변 1 ~ 200자(확인 요청인 동안만, 다시 보내면 고쳐 쓴다). 응답은 VisitReport */
export function replyToVisitReport(reportId, reply) {
  return api.post(`/api/v1/owner/visit-reports/${encodeURIComponent(reportId)}/reply`, { reply });
}

/** 답변 길이(C23) */
export const REPLY_MAX_LENGTH = 200;

/**
 * 방문 금액 정정(D22): '맞음' 금액 신고가 있는 방문 예약만, 신고 한 건에 한 번. 원래 금액 · 수수료는 고치지 않고 정정을 더한다.
 * @returns {Promise<{ reservationId, originalAmount, correctedAmount, feeDifference, settlementMonth, createdAt }>}
 */
export function correctVisitAmount(reservationId, correctedAmount) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(reservationId)}/amount-correction`, { correctedAmount });
}

/** 늦은 방문 처리(D23): '맞음' 미처리 신고가 있는 자동 취소 예약 → 방문. 응답은 방문 처리와 같은 모양 */
export function processLateVisit(reservationId, visitAmount) {
  return api.post(`/api/v1/owner/reservations/${encodeURIComponent(reservationId)}/late-visit`, { visitAmount });
}
