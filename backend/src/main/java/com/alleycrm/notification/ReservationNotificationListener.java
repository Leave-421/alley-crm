package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
import com.alleycrm.reservation.request.ReservationRequestedEvent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 이벤트 → 알림. 커밋 뒤에만 돈다(롤백된 신청에는 알림이 없다).
 * 확정 · 거절 · 변경 · 취소 · 리마인드 등은 그 이벤트가 생길 때 여기에 더한다(C14).
 */
@Component
class ReservationNotificationListener {

    private final NotificationService notifications;
    private final AccountService accountService;

    ReservationNotificationListener(NotificationService notifications, AccountService accountService) {
        this.notifications = notifications;
        this.accountService = accountService;
    }

    /** 새 예약 → 그 가게 사장님(FR-NTF-01 알림함 O-10). 사장님 계정이 아직 없는 가게면 남기지 않는다. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 원래 트랜잭션은 이미 커밋돼 새 트랜잭션에서 저장한다
    void onRequested(ReservationRequestedEvent e) {
        accountService.findOwnerAccountId(e.storeId()).ifPresent(ownerId ->
                notifications.notifyAccount(ownerId, NotificationType.RESERVATION_REQUESTED,
                        Map.of("reservationId", String.valueOf(e.reservationId()))));
    }
}
