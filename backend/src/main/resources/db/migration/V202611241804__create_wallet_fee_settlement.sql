-- D19(#120) DB 기반 2: 가상 잔액 · 소개 수수료 · 월 정산
-- 기준: 기획 저장소 docs/06-data.md 「가상 잔액 · 수수료 · 정산」. 실제 돈은 움직이지 않는다(NFR-02).

CREATE TABLE wallet (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    owner_type  VARCHAR(10)  NOT NULL COMMENT 'customer|store|fund',
    owner_id    BIGINT       NOT NULL COMMENT 'customer.id · store.id · region.id(fund)',
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_owner UNIQUE (owner_type, owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wallet_tx (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    wallet_id       BIGINT       NULL     COMMENT '비회원 예약금은 지갑 없이 guest_phone',
    guest_phone     VARCHAR(20)  NULL,
    type            VARCHAR(30)  NOT NULL COMMENT 'charge|deposit_paid|deposit_refunded|referral_fee_charged|fund_deposited',
    amount          BIGINT       NOT NULL COMMENT '들어오면 +, 나가면 -',
    reservation_id  BIGINT       NULL,
    data_source     VARCHAR(10)  NOT NULL DEFAULT 'real',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_tx_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT fk_wallet_tx_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT ck_wallet_tx_owner CHECK (wallet_id IS NOT NULL OR guest_phone IS NOT NULL),
    KEY ix_wallet_tx_wallet (wallet_id, created_at),
    KEY ix_wallet_tx_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settlement (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    region_id     BIGINT       NOT NULL,
    month         CHAR(7)      NOT NULL COMMENT 'YYYY-MM, 방문 처리된 달',
    status        VARCHAR(12)  NOT NULL DEFAULT 'aggregated' COMMENT 'aggregated|confirmed (확정은 확인 기록, 잔액 안 바뀜)',
    confirmed_at  DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_settlement_month UNIQUE (region_id, month),
    CONSTRAINT fk_settlement_region FOREIGN KEY (region_id) REFERENCES region (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE referral_fee (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    reservation_id      BIGINT        NOT NULL COMMENT '소개 예약, 방문 처리 때 건당 한 번',
    receiving_store_id  BIGINT        NOT NULL COMMENT '낸 가게',
    sender_store_id     BIGINT        NOT NULL COMMENT '보낸 가게(응답에 이름을 내보내지 않음)',
    visit_amount        INT           NOT NULL COMMENT '결제 금액(할인 전)',
    coupon_discount     INT           NOT NULL DEFAULT 0,
    fee_base            INT           NOT NULL COMMENT '수수료 계산 금액(할인 후)',
    fee_rate            DECIMAL(5, 4) NOT NULL COMMENT '생성 시점 수수료율 — 설정이 바뀌어도 그대로',
    fee_amount          INT           NOT NULL,
    coupon_cost         INT           NOT NULL DEFAULT 0 COMMENT '수수료와 따로 기록',
    settlement_month    CHAR(7)       NOT NULL COMMENT 'YYYY-MM',
    settlement_id       BIGINT        NULL,
    data_source         VARCHAR(10)   NOT NULL DEFAULT 'real',
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_referral_fee_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_referral_fee_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_referral_fee_receiving FOREIGN KEY (receiving_store_id) REFERENCES store (id),
    CONSTRAINT fk_referral_fee_sender FOREIGN KEY (sender_store_id) REFERENCES store (id),
    CONSTRAINT fk_referral_fee_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id),
    KEY ix_referral_fee_store_month (receiving_store_id, settlement_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
