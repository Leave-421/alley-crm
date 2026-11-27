package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
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
 * 예약 이벤트 → 알림. 커밋 뒤에만 돈다(롤백된 신청에는 알림이 없다).
 * 변경 요청 · 승인 · 거절, 지연 입력은 그 이벤트가 생길 때 여기에 더한다(C14).
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
        accountService.findOwnerAccountId(e.storeId()).ifPresent(ownerId ->
                notifications.notifyAccount(ownerId, NotificationType.RESERVATION_REQUESTED,
                        Map.of("reservationId", String.valueOf(e.reservationId()))));
    }

    /**
     * 확정 · 거절 · 취소 → 예약한 손님(FR-NTF-03 #1). 손님이 스스로 취소한 것과 방문 처리는 남기지 않는다.
     * 거절이면 그대로 남는 다음 코스 예약 ID를 함께 준다(FR-NTF-03 #2).
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onStatusChanged(ReservationStatusChangedEvent e) {
        String id = String.valueOf(e.reservationId());
        if (ReservationStatus.CONFIRMED.equals(e.toStatus())) {
            notifyCustomer(e, NotificationType.RESERVATION_CONFIRMED, Map.of("reservationId", id));
        } else if (ReservationStatus.CANCELED.equals(e.toStatus())) {
            if (CancelReason.STORE_REJECTED.equals(e.cancelReason())) {
                notifyCustomer(e, NotificationType.RESERVATION_REJECTED, Map.of("reservationId", id,
                        "nextCourseReservationIds", reservations.liveNextCourseIds(e.reservationId())));
            } else if (CancelReason.AUTO_CANCELED.equals(e.cancelReason())) {
                notifyCustomer(e, NotificationType.RESERVATION_CANCELED, Map.of("reservationId", id));
            }
        }
    }

    private void notifyCustomer(ReservationStatusChangedEvent e, String type, Map<String, Object> payload) {
        notifications.notifyReservationCustomer(e.reservationId(), e.customerId(), type, payload);
    }
}
