package com.alleycrm.notification;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.GuestPrincipal;
import com.alleycrm.account.GuestSessionService;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.notification.NotificationService.Counts;
import com.alleycrm.notification.NotificationService.Notification;
import com.alleycrm.store.StoreService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 내 알림 목록 · 읽음 처리(C14), 사장님 실시간 알림(C15). 받는 쪽은 세션(로그인 계정 또는 비회원 제한 세션, C13)으로만 정한다(IDOR). */
@RestController
class NotificationController {

    private final NotificationService notifications;
    private final GuestSessionService guests;
    private final StoreService stores;
    private final OwnerNotificationStream stream;

    NotificationController(NotificationService notifications, GuestSessionService guests, StoreService stores,
                           OwnerNotificationStream stream) {
        this.notifications = notifications;
        this.guests = guests;
        this.stores = stores;
        this.stream = stream;
    }

    /** 사장님 실시간 알림(C15). 세션 쿠키로 인증하고, 그 사장님 계정 알림만 보낸다 */
    @GetMapping(path = "/api/v1/owner/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal AccountPrincipal me) {
        stores.requireApprovedOwnStore(me);
        return stream.subscribe(me.id());
    }

    @GetMapping("/api/v1/notifications")
    Map<String, Object> list(@AuthenticationPrincipal Object principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID);
        }
        List<Notification> found;
        Counts counts;
        if (principal instanceof GuestPrincipal guest) { // 비회원 예약 조회 제한 세션(C13): 그 전화번호 예약들의 알림
            List<Long> reservationIds = guests.reservationIds(guest);
            found = notifications.pageForGuestReservations(reservationIds, page, size);
            counts = notifications.countsForGuestReservations(reservationIds);
        } else {
            long accountId = ((AccountPrincipal) principal).id();
            found = notifications.pageForAccount(accountId, page, size);
            counts = notifications.countsForAccount(accountId);
        }
        List<NotificationResponse> items = found.stream().map(NotificationResponse::of).toList();
        return Map.of("items", items, "page", page, "size", size,
                "totalElements", counts.total(), "totalPages", (counts.total() + size - 1) / size,
                "unreadCount", counts.unread());
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Object principal, @PathVariable long notificationId) {
        if (principal instanceof GuestPrincipal guest) {
            notifications.markGuestNotificationRead(guests.reservationIds(guest), notificationId);
        } else {
            notifications.markAccountNotificationRead(((AccountPrincipal) principal).id(), notificationId);
        }
    }

    /** 계약 `Notification`. ID는 문자열. */
    record NotificationResponse(String id, String type, Map<String, Object> payload, Instant readAt, Instant createdAt) {

        static NotificationResponse of(Notification n) {
            return new NotificationResponse(String.valueOf(n.id()), n.type(), n.payload(), n.readAt(), n.createdAt());
        }
    }
}
