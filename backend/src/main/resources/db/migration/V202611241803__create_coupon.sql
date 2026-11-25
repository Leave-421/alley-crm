-- D19(#120) DB 기반 2: 쿠폰
-- 기준: 기획 저장소 docs/06-data.md 「쿠폰」. 총수량은 조건부 UPDATE로 원자 처리한다(ARCHITECTURE, NFR-09).

CREATE TABLE coupon_policy (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    store_id         BIGINT       NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '끄면 새 발급만 멈춤',
    benefit_type     VARCHAR(20)  NOT NULL COMMENT 'fixed_discount|service',
    discount_amount  INT          NULL     COMMENT 'fixed_discount 금액',
    service_name     VARCHAR(50)  NULL,
    service_value    INT          NULL     COMMENT '서비스 환산 금액(쿠폰 비용 기록용, 수수료 계산에 안 씀)',
    conditions       VARCHAR(200) NULL,
    total_quantity   INT          NOT NULL,
    issued_count     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_policy_store UNIQUE (store_id),
    CONSTRAINT fk_coupon_policy_store FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT ck_coupon_policy_quantity CHECK (issued_count >= 0 AND issued_count <= total_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE coupon (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id           BIGINT       NOT NULL COMMENT '회원만',
    target_store_id       BIGINT       NOT NULL,
    source_base_store_id  BIGINT       NULL,
    source_exposure_id    BIGINT       NULL,
    downloaded_at         DATETIME(6)  NOT NULL,
    expires_at            DATETIME(6)  NOT NULL COMMENT '다운로드 + 7×24시간',
    status                VARCHAR(20)  NOT NULL DEFAULT 'downloaded' COMMENT 'downloaded|applied|used|expired|apply_canceled',
    reservation_id        BIGINT       NULL,
    discount_applied      INT          NULL,
    used_at               DATETIME(6)  NULL,
    data_source           VARCHAR(10)  NOT NULL DEFAULT 'real',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_coupon_target_store FOREIGN KEY (target_store_id) REFERENCES store (id),
    CONSTRAINT fk_coupon_source_store FOREIGN KEY (source_base_store_id) REFERENCES store (id),
    CONSTRAINT fk_coupon_source_exposure FOREIGN KEY (source_exposure_id) REFERENCES recommendation_exposure (id),
    CONSTRAINT fk_coupon_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    KEY ix_coupon_customer_target (customer_id, target_store_id, downloaded_at) COMMENT '30일 제한 계산'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
