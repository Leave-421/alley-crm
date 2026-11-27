package com.alleycrm.visitreport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 방문 신고(06-data VISIT_REPORT). 상태는 접수 → 사장님 확인 요청 → 처리 완료(결과 맞음 · 아님)로만 간다. */
@Entity
@Table(name = "visit_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitReport {

    /** `visitReport.type` (CONTRACTS.md 「3」) */
    public static final String TYPE_AMOUNT = "amount";
    public static final String TYPE_NOT_PROCESSED = "not_processed";
    static final String TYPES = "amount|not_processed";

    /** `visitReport.status` */
    static final String STATUS_RECEIVED = "received";
    static final String STATUS_OWNER_CHECK_REQUESTED = "owner_check_requested";
    static final String STATUS_DONE = "done";
    static final String STATUSES = "received|owner_check_requested|done";

    /** `visitReport.result` */
    static final String RESULT_VALID = "valid";
    static final String RESULTS = "valid|invalid";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "claimed_amount")
    private Integer claimedAmount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "owner_reply", length = 200)
    private String ownerReply;

    @Column(name = "owner_replied_at")
    private Instant ownerRepliedAt;

    @Column(name = "result", length = 10)
    private String result;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static VisitReport received(long reservationId, long customerId, String type, Integer claimedAmount, Instant now) {
        VisitReport r = new VisitReport();
        r.reservationId = reservationId;
        r.customerId = customerId;
        r.type = type;
        r.claimedAmount = claimedAmount;
        r.status = STATUS_RECEIVED;
        r.createdAt = now;
        return r;
    }

    void requestOwnerCheck() {
        status = STATUS_OWNER_CHECK_REQUESTED;
    }

    void reply(String reply, Instant now) {
        ownerReply = reply;
        ownerRepliedAt = now;
    }

    void decide(String result, Instant now) {
        this.status = STATUS_DONE;
        this.result = result;
        this.handledAt = now;
    }
}
