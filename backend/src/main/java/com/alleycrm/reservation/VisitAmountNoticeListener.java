package com.alleycrm.reservation;

import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.service.CouponVisitService;
import com.alleycrm.notification.NotificationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 방문 금액 알림(D21, FR-STL-07 #1 · #2, FR-NTF-03 #6, 기획 11-25 (5)). '방문 처리됨'을 커밋 뒤에 받아
 * <b>회원</b>에게만 `visit_amount_recorded` 알림을 남긴다(비회원은 알림 없음). 손님이 금액의 증인이 되어
 * 다르면 '금액이 달라요' 신고(C23)로 이어진다. 수수료 · 보낸 가게는 넣지 않는다.
 */
@Component
class VisitAmountNoticeListener {

    static final String TYPE = "visit_amount_recorded";

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final CouponVisitService couponVisit;

    VisitAmountNoticeListener(JdbcTemplate jdbc, NotificationService notifications, CouponVisitService couponVisit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.couponVisit = couponVisit;
    }

    @TransactionalEventListener
    void on(VisitProcessedEvent e) {
        List<Long> account = jdbc.queryForList(
                "SELECT account_id FROM customer WHERE id = ? AND type = 'member' AND account_id IS NOT NULL",
                Long.class, e.customerId());
        if (account.isEmpty()) {
            return; // 비회원은 알림 없음(#2)
        }
        String storeName = jdbc.queryForObject("SELECT name FROM store WHERE id = ?", String.class, e.storeId());
        int discount = couponVisit.discountFor(e.reservationId(), e.visitAmount())
                .map(VisitCouponDiscount::discountAmount).orElse(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", String.valueOf(e.reservationId()));
        payload.put("storeId", String.valueOf(e.storeId()));
        payload.put("storeName", storeName);
        payload.put("visitedAt", e.visitedAt().toString());
        payload.put("visitAmount", e.visitAmount());
        payload.put("couponDiscount", discount);
        payload.put("amountAfterDiscount", Math.max(0, e.visitAmount() - discount));
        notifications.notifyAccount(account.get(0), TYPE, payload);
    }
}
