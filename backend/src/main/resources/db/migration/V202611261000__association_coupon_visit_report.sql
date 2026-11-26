-- D24(#159) 11-25 (5): 상인회 쿠폰 · 방문 금액 신고 · 금액 정정.
-- 기준: 기획 저장소 docs/reviews/decision-meeting-2026-11-25-5.md, docs/06-data.md 「ASSOCIATION_COUPON_EVENT」 · 「VISIT_REPORT」 ·
--       「VISIT_AMOUNT_CORRECTION」 · 「COUPON」 · 「WALLET_TX」.

-- 상인회 쿠폰 행사(공동 기금 부담). 확정 뒤 값은 바꾸지 않고 중단만 한다.
CREATE TABLE association_coupon_event (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    region_id             BIGINT       NOT NULL,
    title                 VARCHAR(50)  NOT NULL COMMENT '알림함에 보이는 행사 이름',
    benefit_type          VARCHAR(20)  NOT NULL COMMENT 'fixed_discount|percent_discount (서비스 제공 없음)',
    discount_amount       INT          NULL     COMMENT 'fixed_discount 금액',
    discount_rate         INT          NULL     COMMENT 'percent_discount 할인율(정수 1~50%)',
    max_discount_amount   INT          NULL     COMMENT '한 장 최대 할인액. 정률이면 필수',
    total_quantity        INT          NOT NULL,
    issued_count          INT          NOT NULL DEFAULT 0,
    eligible_store_scope  VARCHAR(10)  NOT NULL COMMENT 'all(모든 참여 가게)|selected(association_coupon_event_store)',
    issue_start_at        DATETIME(6)  NOT NULL COMMENT '받을 수 있는 기간',
    issue_end_at          DATETIME(6)  NOT NULL,
    valid_start_at        DATETIME(6)  NOT NULL COMMENT '쓸 수 있는 기간(예약 신청 시각으로 판단)',
    valid_end_at          DATETIME(6)  NOT NULL,
    reserved_budget       BIGINT       NOT NULL COMMENT '만들 때 기금에서 잡은 예산 = 수량 × 한 장 최대 할인액',
    budget_released_at    DATETIME(6)  NULL     COMMENT '안 쓴 예산을 기금으로 돌려준 시각(valid_end_at 뒤)',
    confirmed_at          DATETIME(6)  NOT NULL,
    stopped_at            DATETIME(6)  NULL     COMMENT '새 발급만 멈춤. 받은 쿠폰은 유효기간까지',
    data_source           VARCHAR(10)  NOT NULL DEFAULT 'real',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_acoupon_event_region FOREIGN KEY (region_id) REFERENCES region (id),
    CONSTRAINT ck_acoupon_event_quantity CHECK (total_quantity >= 1 AND issued_count >= 0 AND issued_count <= total_quantity),
    CONSTRAINT ck_acoupon_event_benefit CHECK (
        (benefit_type = 'fixed_discount' AND discount_amount IS NOT NULL AND discount_amount >= 1 AND discount_rate IS NULL)
        OR (benefit_type = 'percent_discount' AND discount_rate IS NOT NULL AND discount_rate BETWEEN 1 AND 50
            AND max_discount_amount IS NOT NULL AND max_discount_amount >= 1 AND discount_amount IS NULL)),
    CONSTRAINT ck_acoupon_event_scope CHECK (eligible_store_scope IN ('all', 'selected')),
    CONSTRAINT ck_acoupon_event_period CHECK (issue_start_at < issue_end_at AND valid_start_at < valid_end_at AND issue_start_at <= valid_end_at),
    CONSTRAINT ck_acoupon_event_budget CHECK (reserved_budget >= 0),
    KEY ix_acoupon_event_region (region_id, issue_start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 쓸 수 있는 가게(eligible_store_scope = 'selected'일 때). 상인회가 통계를 보고 직접 고른다.
CREATE TABLE association_coupon_event_store (
    event_id  BIGINT NOT NULL,
    store_id  BIGINT NOT NULL,
    PRIMARY KEY (event_id, store_id),
    CONSTRAINT fk_acoupon_store_event FOREIGN KEY (event_id) REFERENCES association_coupon_event (id),
    CONSTRAINT fk_acoupon_store_store FOREIGN KEY (store_id) REFERENCES store (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 쿠폰: 가게 쿠폰(store) · 상인회 쿠폰(association). 상인회 쿠폰은 가게 · 행사 칸이 비고 상인회 행사를 채운다.
ALTER TABLE coupon
    MODIFY coupon_policy_id BIGINT NULL COMMENT '가게 쿠폰만',
    MODIFY target_store_id  BIGINT NULL COMMENT '가게 쿠폰만(상인회 쿠폰은 행사의 쓸 수 있는 가게)',
    MODIFY expires_at DATETIME(6) NOT NULL COMMENT '가게: 다운로드 + valid_days × 24시간 / 상인회: 행사 valid_end_at',
    ADD COLUMN issuer_kind          VARCHAR(12) NOT NULL DEFAULT 'store' COMMENT 'store|association' AFTER customer_id,
    ADD COLUMN association_event_id BIGINT      NULL AFTER coupon_policy_id,
    ADD CONSTRAINT fk_coupon_association_event FOREIGN KEY (association_event_id) REFERENCES association_coupon_event (id),
    ADD CONSTRAINT uk_coupon_association_member UNIQUE (association_event_id, customer_id) COMMENT '같은 상인회 행사 · 같은 회원은 1장',
    ADD CONSTRAINT ck_coupon_issuer CHECK (
        (issuer_kind = 'store' AND coupon_policy_id IS NOT NULL AND target_store_id IS NOT NULL AND association_event_id IS NULL)
        OR (issuer_kind = 'association' AND association_event_id IS NOT NULL AND coupon_policy_id IS NULL));

-- 회원의 방문 금액 신고 · 미처리 신고. 상인회가 진위만 판단하고, 허위 신고에 페널티는 없다.
CREATE TABLE visit_report (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_id   BIGINT       NOT NULL,
    customer_id      BIGINT       NOT NULL COMMENT '회원만',
    type             VARCHAR(16)  NOT NULL COMMENT 'amount(금액이 달라요)|not_processed(방문했어요, 자동 취소된 예약만)',
    claimed_amount   INT          NULL     COMMENT '금액 신고에서 회원이 말한 금액(선택)',
    status           VARCHAR(20)  NOT NULL DEFAULT 'received' COMMENT 'received|owner_check_requested|done',
    owner_reply      VARCHAR(200) NULL     COMMENT '사장님 답변(상인회가 봄)',
    owner_replied_at DATETIME(6)  NULL,
    result           VARCHAR(10)  NULL     COMMENT 'valid(맞음)|invalid(아님). done일 때만',
    handled_at       DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_visit_report_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_visit_report_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_visit_report_type CHECK (type IN ('amount', 'not_processed')),
    CONSTRAINT ck_visit_report_status CHECK (status IN ('received', 'owner_check_requested', 'done')),
    CONSTRAINT ck_visit_report_result CHECK ((status = 'done') = (result IS NOT NULL) AND (result IS NULL OR result IN ('valid', 'invalid'))),
    CONSTRAINT ck_visit_report_claimed CHECK (claimed_amount IS NULL OR claimed_amount >= 0),
    KEY ix_visit_report_reservation (reservation_id),
    KEY ix_visit_report_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 거래: 상인회 쿠폰 예산이 어느 행사 것인지 잇는다.
ALTER TABLE wallet_tx
    MODIFY type VARCHAR(30) NOT NULL COMMENT 'charge|deposit_paid|deposit_refunded|referral_fee_charged|fund_deposited|fund_budget_reserved|fund_budget_released|fee_difference_charged|fee_difference_deposited',
    ADD COLUMN association_event_id BIGINT NULL COMMENT '기금 예산 잡기 · 해제의 상인회 행사' AFTER reservation_id,
    ADD CONSTRAINT fk_wallet_tx_association_event FOREIGN KEY (association_event_id) REFERENCES association_coupon_event (id);

-- 방문 금액 정정: 원래 예약 · 수수료 기록은 그대로 두고 정정과 수수료 차액만 남긴다.
CREATE TABLE visit_amount_correction (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_id    BIGINT       NOT NULL,
    report_id         BIGINT       NOT NULL COMMENT "'맞음'으로 기록된 금액 신고",
    original_amount   INT          NOT NULL,
    corrected_amount  INT          NOT NULL,
    fee_difference    INT          NOT NULL DEFAULT 0 COMMENT '수수료 차액(소개 예약이 아니면 0)',
    wallet_tx_id      BIGINT       NULL     COMMENT '수수료 차액 차감 거래',
    settlement_month  CHAR(7)      NOT NULL COMMENT '원래 방문(예약) 달 YYYY-MM',
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_correction_report UNIQUE (report_id) COMMENT '신고 한 건에 정정 한 번',
    CONSTRAINT fk_correction_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_correction_report FOREIGN KEY (report_id) REFERENCES visit_report (id),
    CONSTRAINT fk_correction_wallet_tx FOREIGN KEY (wallet_tx_id) REFERENCES wallet_tx (id),
    CONSTRAINT ck_correction_amounts CHECK (original_amount >= 0 AND corrected_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
