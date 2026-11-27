package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
import com.alleycrm.recommend.NextStoreRecommendedEvent;
import com.alleycrm.reservation.CancelReason;
import com.alleycrm.reservation.ReservationNoticeReader;
import com.alleycrm.reservation.ReservationStatus;
import com.alleycrm.reservation.ReservationStatusChangedEvent;
import com.alleycrm.reservation.request.ReservationRequestedEvent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 이벤트 → 알림. 커밋 뒤에만 돈다(롤백된 신청에는 알림이 없다). 지연 입력 알림은 D14 DelayNoticeListener가 만든다.
 */
@Component
class ReservationNotificationListener {

    private final NotificationService notifications;
    private final AccountService accountService;
    private final ReservationNoticeReader reservations;

    ReservationNotificationListener(NotificationService notifications, AccountService accountService,
                                    ReservationNoticeReader reservations) {
        this.notifications = notifications;
        this.accountService = accountService;
        this.reservations = reservations;
    }

    /** 새 예약 → 그 가게 사장님(FR-NTF-01 알림함 O-10). 사장님 계정이 아직 없는 가게면 남기지 않는다. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 원래 트랜잭션은 이미 커밋돼 새 트랜잭션에서 저장한다
    void onRequested(ReservationRequestedEvent e) {
        notifyOwner(e.storeId(), NotificationType.RESERVATION_REQUESTED, Map.of("reservationId", String.valueOf(e.reservationId())));
    }

    /**
     * 상태 변경 → 알림.
     * <ul>
     *   <li>예약 변경(D8): 요청 → 사장님 change_requested, 승인 · 거절(자동 거절 포함) → 손님 change_approved · change_rejected</li>
     *   <li>확정 · 거절 · 자동 취소 → 예약한 손님(FR-NTF-03 #1). 거절이면 그대로 남는 다음 코스 예약 ID를 함께 준다(#2)</li>
     *   <li>손님이 스스로 취소 → 사장님 reservation_canceled(FR-NTF-01 #2). 손님 본인에게는 남기지 않는다</li>
     * </ul>
     * 방문 처리는 남기지 않는다(방문 금액 알림은 D21).
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onStatusChanged(ReservationStatusChangedEvent e) {
        String id = String.valueOf(e.reservationId());
        if (e.change() != null) {
            switch (e.change()) {
                case ReservationStatusChangedEvent.CHANGE_REQUESTED ->
                        notifyOwner(e.storeId(), NotificationType.CHANGE_REQUESTED, Map.of("reservationId", id));
                case ReservationStatusChangedEvent.CHANGE_APPROVED ->
                        notifyCustomer(e, NotificationType.CHANGE_APPROVED, Map.of("reservationId", id));
                case ReservationStatusChangedEvent.CHANGE_REJECTED ->
                        notifyCustomer(e, NotificationType.CHANGE_REJECTED, Map.of("reservationId", id));
                default -> throw new IllegalStateException("알 수 없는 예약 변경 값: " + e.change());
            }
            return;
        }
        if (ReservationStatus.CONFIRMED.equals(e.toStatus())) {
            notifyCustomer(e, NotificationType.RESERVATION_CONFIRMED, Map.of("reservationId", id));
        } else if (ReservationStatus.CANCELED.equals(e.toStatus())) {
            if (CancelReason.STORE_REJECTED.equals(e.cancelReason())) {
                notifyCustomer(e, NotificationType.RESERVATION_REJECTED, Map.of("reservationId", id,
                        "nextCourseReservationIds", reservations.liveNextCourseIds(e.reservationId())));
            } else if (CancelReason.AUTO_CANCELED.equals(e.cancelReason())) {
                notifyCustomer(e, NotificationType.RESERVATION_CANCELED, Map.of("reservationId", id));
            } else if (CancelReason.CUSTOMER_CANCELED.equals(e.cancelReason())) {
                notifyOwner(e.storeId(), NotificationType.RESERVATION_CANCELED, Map.of("reservationId", id));
            }
        }
    }

    /** 예약 뒤 다음 가게 추천 후보가 있음(E8) → 그 예약의 손님(회원 계정 · 비회원 예약)에게(FR-CRS-02, 3.11 N-1) */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onNextStoreRecommended(NextStoreRecommendedEvent e) {
        notifications.notifyReservationCustomer(e.reservationId(), e.customerId(), NotificationType.NEXT_STORE_RECOMMENDED,
                Map.of("reservationId", String.valueOf(e.reservationId()), "baseStoreId", String.valueOf(e.storeId())));
    }

    /** 사장님 계정이 아직 없는 가게면 남기지 않는다 */
    private void notifyOwner(long storeId, String type, Map<String, Object> payload) {
        accountService.findOwnerAccountId(storeId).ifPresent(ownerId -> notifications.notifyAccount(ownerId, type, payload));
    }

    private void notifyCustomer(ReservationStatusChangedEvent e, String type, Map<String, Object> payload) {
        notifications.notifyReservationCustomer(e.reservationId(), e.customerId(), type, payload);
    }
}
