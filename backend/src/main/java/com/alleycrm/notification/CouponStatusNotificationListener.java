package com.alleycrm.notification;

import com.alleycrm.coupon.CouponStatusChangedEvent;
import com.alleycrm.customer.CustomerService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 쿠폰 사용 완료 · 기간 만료 → 그 회원 계정(coupon_status, FR-NTF-03 #4, 3.6 S-4). 비회원에게는 없다(#5). 커밋 뒤에만 돈다. */
@Component
class CouponStatusNotificationListener {

    private final NotificationService notifications;
    private final CustomerService customers;

    CouponStatusNotificationListener(NotificationService notifications, CustomerService customers) {
        this.notifications = notifications;
        this.customers = customers;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onChanged(CouponStatusChangedEvent e) {
        customers.memberAccountId(e.customerId()).ifPresent(accountId ->
                notifications.notifyAccount(accountId, NotificationType.COUPON_STATUS,
                        Map.of("couponId", String.valueOf(e.couponId()), "status", e.status())));
    }
}
