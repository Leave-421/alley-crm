package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
import com.alleycrm.coupon.AssociationCouponOpenedEvent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 상인회 쿠폰 행사 → 회원 계정 전부(FR-CPN-13, CONTRACTS_API 3.9 U-1). 비회원 예약에는 만들지 않는다. 커밋 뒤에만 돈다. */
@Component
class AssociationCouponNotificationListener {

    private final NotificationService notifications;
    private final AccountService accountService;

    AssociationCouponNotificationListener(NotificationService notifications, AccountService accountService) {
        this.notifications = notifications;
        this.accountService = accountService;
    }

    // shortcut: 회원마다 INSERT 한 번, 한 골목 회원 수(수백)면 충분하다. 수만 명이 되면 INSERT ... SELECT 한 번으로 바꾼다
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onOpened(AssociationCouponOpenedEvent e) {
        Map<String, Object> payload = Map.of("associationCouponEventId", String.valueOf(e.eventId()));
        for (long accountId : accountService.customerAccountIds()) {
            notifications.notifyAccount(accountId, NotificationType.ASSOCIATION_COUPON_OPENED, payload);
        }
    }
}
