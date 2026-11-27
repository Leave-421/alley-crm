package com.alleycrm.notification;

import com.alleycrm.common.scheduler.ScheduledJob;
import com.alleycrm.reservation.ReservationNoticeReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 리마인드(FR-NTF-03 #3): 방문 30분 전 안에 든 확정 예약의 손님에게 reminder 알림을 한 번 남긴다.
 * 화면은 이 알림에서 늦을 것 같아요(G-07)로 보낸다. 이미 남긴 예약은 건너뛰어 여러 번 돌아도 한 건이다.
 */
@Component
class ReservationReminderJob implements ScheduledJob {

    static final Duration LEAD_TIME = Duration.ofMinutes(30);

    private final ReservationNoticeReader reservations;
    private final NotificationService notifications;
    private final Clock clock;

    ReservationReminderJob(ReservationNoticeReader reservations, NotificationService notifications, Clock clock) {
        this.reservations = reservations;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "reservation_reminder";
    }

    @Override
    public int run() {
        Instant now = Instant.now(clock);
        int n = 0;
        for (ReservationNoticeReader.Upcoming u : reservations.confirmedStartingIn(now, now.plus(LEAD_TIME))) {
            if (!notifications.existsForReservation(NotificationType.REMINDER, u.reservationId())) {
                notifications.notifyReservationCustomer(u.reservationId(), u.customerId(), NotificationType.REMINDER,
                        Map.of("reservationId", String.valueOf(u.reservationId())));
                n++;
            }
        }
        return n;
    }
}
