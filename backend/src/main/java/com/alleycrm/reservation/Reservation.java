package com.alleycrm.reservation;

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

/** 예약(06-data RESERVATION). 테이블은 Flyway가 만들고, 여기서는 지금 쓰는 칸만 매핑한다. */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_no", nullable = false, length = 20)
    private String reservationNo;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "party_size", nullable = false)
    private Integer partySize;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cancel_reason", length = 30)
    private String cancelReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "deposit_amount", nullable = false)
    private Integer depositAmount;

    @Column(name = "request_note", length = 200)
    private String requestNote;

    @Column(name = "course_root_id")
    private Long courseRootId;

    @Column(name = "course_proposal_id")
    private Long courseProposalId;

    @Column(name = "sender_store_id")
    private Long senderStoreId;

    @Column(name = "data_source", nullable = false, length = 10)
    private String dataSource;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "visited_at")
    private Instant visitedAt;

    /** 돌려준(돌려줄) 예약금. 손님 취소 때 정한다(D6) */
    @Column(name = "refund_amount")
    private Integer refundAmount;

    /** 방문 처리 때 사장님이 넣은 결제 금액(쿠폰 할인 전) */
    @Column(name = "visit_amount")
    private Integer visitAmount;

    /** 확정 → 방문(D11). 상태 확인은 부르는 쪽(ReservationStateService)이 한다 */
    void visit(int amount, Instant now) {
        this.status = ReservationStatus.VISITED;
        this.visitAmount = amount;
        this.visitedAt = now;
    }

    /** 신청 → 확정(D3). 상태 확인은 부르는 쪽(ReservationStateService)이 한다 */
    void confirm(Instant now) {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    /** → 취소. 사유는 CancelReason 값 */
    void cancel(String reason, Instant now) {
        this.status = ReservationStatus.CANCELED;
        this.cancelReason = reason;
        this.canceledAt = now;
    }

    /** 손님 취소(D6): 돌려줄 예약금을 같이 남긴다. 실제 환불은 DepositRefundListener가 이 금액으로 한다 */
    void cancelByCustomer(int refund, Instant now) {
        cancel(CancelReason.CUSTOMER_CANCELED, now);
        this.refundAmount = refund;
    }

    /** 신청 상태의 새 예약. 값 검사는 신청 단계들이 끝낸 뒤 부른다. */
    public static Reservation requested(String reservationNo, Long storeId, Long customerId, Instant startAt,
            int partySize, Instant requestedAt, int depositAmount, String requestNote,
            Long courseRootId, Long courseProposalId, Long senderStoreId) {
        Reservation r = new Reservation();
        r.reservationNo = reservationNo;
        r.storeId = storeId;
        r.customerId = customerId;
        r.startAt = startAt;
        r.partySize = partySize;
        r.status = ReservationStatus.REQUESTED;
        r.requestedAt = requestedAt;
        r.depositAmount = depositAmount;
        r.requestNote = requestNote;
        r.courseRootId = courseRootId;
        r.courseProposalId = courseProposalId;
        r.senderStoreId = senderStoreId;
        r.dataSource = "real";
        return r;
    }
}
