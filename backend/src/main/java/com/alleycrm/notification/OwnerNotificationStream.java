package com.alleycrm.notification;

import com.alleycrm.notification.NotificationService.NotificationStoredEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사장님 실시간 알림(C15, FR-NTF-01 · NFR-05). 계정마다 열린 SSE 연결을 들고 있다가, 그 계정 알림이 저장되고
 * 커밋되면 'notification' 이벤트로 한 건씩 보낸다. 다른 계정 알림은 보내지 않는다(다른 가게 사장님 격리).
 * shortcut: 연결은 이 서버 메모리에만 있다(단일 애플리케이션). 서버를 여러 대 띄우면 공유 저장소 · 브로커가 필요하다.
 */
@Component
class OwnerNotificationStream {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotificationStream.class);
    /** 끊기면 브라우저 EventSource가 다시 붙는다 */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter subscribe(long accountId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> mine = emitters.computeIfAbsent(accountId, id -> new CopyOnWriteArrayList<>());
        mine.add(emitter);
        Runnable remove = () -> mine.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connected")); // 응답 머리를 바로 보내 연결을 연다
        } catch (IOException e) {
            mine.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 알림 저장이 커밋된 뒤에만 보낸다(롤백된 알림은 보내지 않음) */
    @TransactionalEventListener
    void onStored(NotificationStoredEvent e) {
        if (!NotificationType.RECIPIENT_ACCOUNT.equals(e.recipientType())) {
            return;
        }
        List<SseEmitter> targets = emitters.getOrDefault(e.recipientId(), List.of());
        if (targets.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("notification")
                .id(String.valueOf(e.notification().id()))
                .data(NotificationController.NotificationResponse.of(e.notification()), MediaType.APPLICATION_JSON);
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException ex) {
                // 브라우저가 닫혀 끊긴 연결. 목록에서 빼고, 알림은 DB에 있어 알림함에서 다시 본다
                targets.remove(emitter);
                log.debug("SSE 연결 정리: account {}", e.recipientId(), ex);
            }
        }
    }
}
