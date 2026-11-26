package com.alleycrm.notification;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 알림을 만들고 읽는 단 한 곳(C14). 다른 기능은 이벤트를 내보내고, 커밋 뒤 리스너가 여기를 부른다.
 * payload에는 화면 이동용 ID와 값만 넣는다(손님 개인정보 · 보낸 가게 금지, FR-CUS-05).
 */
@Service
public class NotificationService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;

    NotificationService(JdbcTemplate jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    /** 회원 · 사장님 계정에게 */
    @Transactional
    public void notifyAccount(long accountId, String type, Map<String, Object> payload) {
        insert(NotificationType.RECIPIENT_ACCOUNT, accountId, type, payload);
    }

    /** 비회원 예약에게(비회원 예약 조회 제한 세션에서 본다, C13) */
    @Transactional
    public void notifyGuestReservation(long reservationId, String type, Map<String, Object> payload) {
        insert(NotificationType.RECIPIENT_GUEST_RESERVATION, reservationId, type, payload);
    }

    /** 계정의 알림, 최근 것부터 */
    @Transactional(readOnly = true)
    public List<Notification> pageForAccount(long accountId, int page, int size) {
        return page(NotificationType.RECIPIENT_ACCOUNT, List.of(accountId), page, size);
    }

    @Transactional(readOnly = true)
    public Counts countsForAccount(long accountId) {
        return counts(NotificationType.RECIPIENT_ACCOUNT, List.of(accountId));
    }

    /** 받은 사람만 읽음 처리할 수 있다. 남의 알림은 존재를 숨기고 404. 이미 읽었으면 처음 시각을 유지한다. */
    @Transactional
    public void markAccountNotificationRead(long accountId, long notificationId) {
        markRead(NotificationType.RECIPIENT_ACCOUNT, List.of(accountId), notificationId);
    }

    /** 비회원 예약 조회 제한 세션(C13): 그 세션이 다루는 예약들의 알림, 최근 것부터 */
    @Transactional(readOnly = true)
    public List<Notification> pageForGuestReservations(List<Long> reservationIds, int page, int size) {
        return page(NotificationType.RECIPIENT_GUEST_RESERVATION, reservationIds, page, size);
    }

    @Transactional(readOnly = true)
    public Counts countsForGuestReservations(List<Long> reservationIds) {
        return counts(NotificationType.RECIPIENT_GUEST_RESERVATION, reservationIds);
    }

    /** 그 세션 예약의 알림이 아니면 404. */
    @Transactional
    public void markGuestNotificationRead(List<Long> reservationIds, long notificationId) {
        markRead(NotificationType.RECIPIENT_GUEST_RESERVATION, reservationIds, notificationId);
    }

    private List<Notification> page(String recipientType, List<Long> recipientIds, int page, int size) {
        if (recipientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, type, payload, read_at, created_at FROM notification
                WHERE recipient_type = ? AND recipient_id IN (%s)
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(placeholders(recipientIds)), rowMapper(),
                args(recipientType, recipientIds, size, (long) page * size));
    }

    private Counts counts(String recipientType, List<Long> recipientIds) {
        if (recipientIds.isEmpty()) {
            return new Counts(0, 0);
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total, COALESCE(SUM(read_at IS NULL), 0) AS unread FROM notification
                WHERE recipient_type = ? AND recipient_id IN (%s)
                """.formatted(placeholders(recipientIds)),
                (rs, i) -> new Counts(rs.getLong("total"), rs.getLong("unread")), args(recipientType, recipientIds));
    }

    private void markRead(String recipientType, List<Long> recipientIds, long notificationId) {
        Integer mine = recipientIds.isEmpty() ? Integer.valueOf(0) : jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification WHERE id = ? AND recipient_type = ? AND recipient_id IN (%s)
                """.formatted(placeholders(recipientIds)), Integer.class,
                args(notificationId, recipientType, recipientIds));
        if (mine == null || mine == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        jdbc.update("UPDATE notification SET read_at = ? WHERE id = ? AND read_at IS NULL", now(), notificationId);
    }

    /** IN 절의 자리표시자. 값은 항상 매개변수로 바인딩한다. */
    private static String placeholders(List<Long> ids) {
        return String.join(", ", Collections.nCopies(ids.size(), "?"));
    }

    /** 앞 값 · ID 목록 · 뒤 값을 바인딩 순서대로 편다. */
    private static Object[] args(Object... parts) {
        List<Object> out = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof List<?> list) {
                out.addAll(list);
            } else {
                out.add(part);
            }
        }
        return out.toArray();
    }

    private void insert(String recipientType, long recipientId, String type, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO notification (recipient_type, recipient_id, type, payload, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, recipientType, recipientId, type, json.writeValueAsString(payload == null ? Map.of() : payload), now());
    }

    private RowMapper<Notification> rowMapper() {
        return (rs, i) -> {
            String payload = rs.getString("payload");
            LocalDateTime readAt = rs.getObject("read_at", LocalDateTime.class);
            return new Notification(
                    rs.getLong("id"),
                    rs.getString("type"),
                    payload == null ? Map.of() : json.readValue(payload, PAYLOAD_TYPE),
                    readAt == null ? null : readAt.toInstant(ZoneOffset.UTC),
                    rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        };
    }

    /** DB 시각은 UTC 벽시계. 시연 시계(D18)를 따른다 */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    public record Notification(long id, String type, Map<String, Object> payload, Instant readAt, Instant createdAt) {
    }

    public record Counts(long total, long unread) {
    }
}
