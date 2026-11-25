package com.alleycrm.notification;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.notification.NotificationService.Counts;
import com.alleycrm.notification.NotificationService.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 알림 목록 · 읽음 처리(C14). 받는 쪽은 로그인 계정으로만 정한다(IDOR). */
@RestController
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/notifications")
    Map<String, Object> list(@AuthenticationPrincipal AccountPrincipal me,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID);
        }
        List<NotificationResponse> items = notifications.pageForAccount(me.id(), page, size).stream()
                .map(NotificationResponse::of).toList();
        Counts counts = notifications.countsForAccount(me.id());
        return Map.of("items", items, "page", page, "size", size,
                "totalElements", counts.total(), "totalPages", (counts.total() + size - 1) / size,
                "unreadCount", counts.unread());
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long notificationId) {
        notifications.markAccountNotificationRead(me.id(), notificationId);
    }

    /** 계약 `Notification`. ID는 문자열. */
    record NotificationResponse(String id, String type, Map<String, Object> payload, Instant readAt, Instant createdAt) {

        static NotificationResponse of(Notification n) {
            return new NotificationResponse(String.valueOf(n.id()), n.type(), n.payload(), n.readAt(), n.createdAt());
        }
    }
}
