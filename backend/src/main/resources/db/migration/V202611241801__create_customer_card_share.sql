-- D19(#120) DB 기반 2: 고객 카드 · 공유 동의 · 공유 정책
-- 기준: 기획 저장소 docs/06-data.md 「손님 · 방문자 · 고객 카드」.

CREATE TABLE customer_card (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    store_id       BIGINT       NOT NULL,
    customer_id    BIGINT       NOT NULL,
    visit_count    INT          NOT NULL DEFAULT 0 COMMENT '그 가게 방문 처리 기준',
    last_visit_at  DATETIME(6)  NULL,
    memo           VARCHAR(500) NULL     COMMENT '사장님 메모, 비회원 파기 대상',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_customer_card UNIQUE (store_id, customer_id),
    CONSTRAINT fk_customer_card_store FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT fk_customer_card_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE share_consent (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_id  BIGINT       NOT NULL,
    consented_at    DATETIME(6)  NOT NULL,
    items           JSON         NOT NULL COMMENT '동의 시점에 공유된 항목 목록(share item 값)',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_share_consent_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_share_consent_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 이름 · 연락처 · 방문한 가게 목록은 항상 켜짐이라 행으로 두지 않는다(11-24 (5)).
CREATE TABLE share_policy (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    region_id   BIGINT       NOT NULL,
    item        VARCHAR(30)  NOT NULL COMMENT 'party_size|visit_datetime|gender_age_group',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_share_policy UNIQUE (region_id, item),
    CONSTRAINT fk_share_policy_region FOREIGN KEY (region_id) REFERENCES region (id),
    CONSTRAINT ck_share_policy_item CHECK (item IN ('party_size', 'visit_datetime', 'gender_age_group'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
