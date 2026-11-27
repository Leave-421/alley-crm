package com.alleycrm.reservation;

import com.alleycrm.notification.NotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 지연 입력 알림(D14, FR-RSV-10 #2): 커밋 뒤에 그 가게 사장님 계정에 `delay_reported` 알림을 남긴다.
 * payload: 예약 id · 원래 예약 시각 · 예상 도착 시각. 손님 이름 · 연락처는 넣지 않는다.
 */
@Component
class DelayNoticeListener {

    static final String TYPE = "delay_reported";

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    DelayNoticeListener(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @TransactionalEventListener
    void on(DelayReportedEvent e) {
        List<Long> owner = jdbc.queryForList("SELECT id FROM account WHERE role = 'owner' AND store_id = ?", Long.class, e.storeId());
        if (owner.isEmpty()) {
            return;
        }
        notifications.notifyAccount(owner.get(0), TYPE, Map.of(
                "reservationId", String.valueOf(e.reservationId()),
                "startAt", e.startAt().toString(),
                "expectedArrivalAt", e.expectedArrivalAt().toString()));
    }
}
