package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
import com.alleycrm.visitreport.VisitReportOwnerCheckRequestedEvent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 상인회 신고 확인 요청 → 그 가게 사장님(visit_report_check_requested, FR-STL-08). 커밋 뒤에만 돈다. */
@Component
class VisitReportNotificationListener {

    private final NotificationService notifications;
    private final AccountService accountService;

    VisitReportNotificationListener(NotificationService notifications, AccountService accountService) {
        this.notifications = notifications;
        this.accountService = accountService;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onOwnerCheckRequested(VisitReportOwnerCheckRequestedEvent e) {
        accountService.findOwnerAccountId(e.storeId()).ifPresent(ownerId ->
                notifications.notifyAccount(ownerId, NotificationType.VISIT_REPORT_CHECK_REQUESTED, Map.of(
                        "visitReportId", String.valueOf(e.visitReportId()),
                        "reservationId", String.valueOf(e.reservationId()))));
    }
}
