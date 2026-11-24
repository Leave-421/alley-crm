-- D1(#12) DB 기반 1: 손님 · 익명 방문자
-- 기준: 기획 저장소 docs/06-data.md 「손님 · 방문자 · 고객 카드」. 고객 카드 · 공유 동의는 D19.

CREATE TABLE customer (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    type            VARCHAR(10)  NOT NULL COMMENT 'member|guest',
    account_id      BIGINT       NULL     COMMENT 'member만',
    name            VARCHAR(20)  NULL     COMMENT '비회원 필수, 파기 대상',
    phone           VARCHAR(20)  NULL     COMMENT '비회원 필수, 파기 대상',
    gender          VARCHAR(10)  NULL     COMMENT 'female|male, null = 응답 안 함',
    age_group       VARCHAR(20)  NULL     COMMENT 'age_20_or_under|age_30s|age_40s|age_50_or_over',
    last_visit_at   DATETIME(6)  NULL,
    last_cancel_at  DATETIME(6)  NULL,
    purged_at       DATETIME(6)  NULL     COMMENT '비회원 파기 시각',
    data_source     VARCHAR(10)  NOT NULL DEFAULT 'real' COMMENT 'real|demo|public',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_customer_account UNIQUE (account_id),
    CONSTRAINT fk_customer_account FOREIGN KEY (account_id) REFERENCES account (id),
    KEY ix_customer_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE visitor (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    anon_id      CHAR(36)    NOT NULL COMMENT '브라우저에 두는 익명 ID(UUID)',
    customer_id  BIGINT      NULL     COMMENT '식별되면 채움',
    linked_at    DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_visitor_anon UNIQUE (anon_id),
    CONSTRAINT fk_visitor_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
