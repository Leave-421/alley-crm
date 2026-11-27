package com.alleycrm.visitreport;

import java.time.Instant;

/** 신고 API 응답들. 상인회 · 사장님 응답에도 손님 이름 · 연락처는 넣지 않는다(FR-STL-08 #5). */
final class VisitReportResponses {

    /** 회원이 보는 내 신고(G-04 결과 표시). 사장님 답변은 넣지 않는다 */
    record Mine(String id, String reservationId, String type, Integer claimedAmount, String status, String result,
                Instant createdAt, Instant handledAt) {

        static Mine of(VisitReport r) {
            return new Mine(String.valueOf(r.getId()), String.valueOf(r.getReservationId()), r.getType(),
                    r.getClaimedAmount(), r.getStatus(), r.getResult(), r.getCreatedAt(), r.getHandledAt());
        }
    }

    /**
     * 상인회 · 사장님이 보는 신고 건: 가게 · 예약 번호 · 방문 일시 · 사장님이 넣은 금액 · 손님이 신고한 금액 · 사장님 답변.
     *
     * @param startAt 예약(방문) 일시
     * @param month 정산에 들어가는 달(예약 시작 달, 운영 시간대) YYYY-MM
     * @param visitAmount 사장님이 넣은 방문 금액. 방문 처리 안 된 예약(미처리 신고)이면 null
     */
    record Detail(String id, String type, String status, String result, String storeId, String storeName,
                  String reservationId, String reservationNo, Instant startAt, String month, Integer visitAmount,
                  Integer claimedAmount, String ownerReply, Instant ownerRepliedAt, Instant createdAt, Instant handledAt) {
    }

    private VisitReportResponses() {
    }
}
