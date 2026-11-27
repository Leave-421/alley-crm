package com.alleycrm.notification;

import com.alleycrm.account.AccountService;
import com.alleycrm.store.StoreJoinDecidedEvent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 가게 가입 승인 · 거절 → 그 가게 사장님(store_join_result). 커밋 뒤에만 돈다. */
@Component
class StoreJoinNotificationListener {

    private final NotificationService notifications;
    private final AccountService accountService;

    StoreJoinNotificationListener(NotificationService notifications, AccountService accountService) {
        this.notifications = notifications;
        this.accountService = accountService;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onDecided(StoreJoinDecidedEvent e) {
        accountService.findOwnerAccountId(e.storeId()).ifPresent(ownerId ->
                notifications.notifyAccount(ownerId, NotificationType.STORE_JOIN_RESULT,
                        Map.of("storeId", String.valueOf(e.storeId()), "joinStatus", e.joinStatus())));
    }
}
