-- D19(#120) DB 기반 2: 알림
-- 기준: 기획 저장소 docs/06-data.md 「알림 · 설정 · 공공데이터」. type 값은 docs/CONTRACTS.md 「3」(C14에서 늘림).

CREATE TABLE notification (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_type  VARCHAR(20)  NOT NULL COMMENT 'account|guest_reservation',
    recipient_id    BIGINT       NOT NULL COMMENT 'account.id 또는 reservation.id',
    type            VARCHAR(40)  NOT NULL,
    payload         JSON         NULL,
    read_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_notification_recipient (recipient_type, recipient_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
