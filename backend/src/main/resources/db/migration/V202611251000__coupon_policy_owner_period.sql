-- 쿠폰 행사: 사장님이 기간 · 정률 할인을 정하고, 확정 뒤 잠그고, 중단 뒤 새 행사를 만든다.
-- 기준: 기획 저장소 docs/reviews/decision-meeting-2026-11-25.md, docs/06-data.md 「쿠폰」.

-- 가게당 행 1개 → 행사마다 행 1개. FK가 쓰는 인덱스를 먼저 만든 뒤 UNIQUE를 지운다.
ALTER TABLE coupon_policy
    ADD KEY ix_coupon_policy_store (store_id);

ALTER TABLE coupon_policy
    DROP INDEX uk_coupon_policy_store,
    DROP COLUMN enabled,
    MODIFY benefit_type VARCHAR(20) NOT NULL COMMENT 'fixed_discount|percent_discount|service. 확정 뒤 변경 불가',
    ADD COLUMN discount_rate        INT         NULL COMMENT 'percent_discount 할인율(정수 1~50%)' AFTER discount_amount,
    ADD COLUMN max_discount_amount  INT         NULL COMMENT 'percent_discount 최대 할인액(비우면 상한 없음)' AFTER discount_rate,
    ADD COLUMN valid_days           INT         NOT NULL DEFAULT 7 COMMENT '쿠폰 예약 기한(1~30일)' AFTER max_discount_amount,
    ADD COLUMN confirmed_at         DATETIME(6) NULL COMMENT '처음 확정 시각. 값이 있으면 모든 설정값 변경 불가' AFTER total_quantity,
    ADD COLUMN stopped_at           DATETIME(6) NULL COMMENT '중단 시각. 새 발급만 멈추고 받은 쿠폰은 유지. 다시 켤 수 없음' AFTER confirmed_at,
    ADD COLUMN active_store_id      BIGINT      AS (IF(stopped_at IS NULL, store_id, NULL)) STORED COMMENT '진행 중인 행사는 가게당 하나(UNIQUE용)',
    ADD CONSTRAINT uk_coupon_policy_active UNIQUE (active_store_id),
    ADD CONSTRAINT ck_coupon_policy_valid_days CHECK (valid_days BETWEEN 1 AND 30),
    ADD CONSTRAINT ck_coupon_policy_rate CHECK (discount_rate IS NULL OR discount_rate BETWEEN 1 AND 50),
    ADD CONSTRAINT ck_coupon_policy_max_discount CHECK (max_discount_amount IS NULL OR max_discount_amount > 0);

-- 30일 제한은 같은 회원 · 같은 행사 단위로 센다.
ALTER TABLE coupon
    ADD COLUMN coupon_policy_id BIGINT NOT NULL AFTER customer_id,
    MODIFY expires_at DATETIME(6) NOT NULL COMMENT '다운로드 + coupon_policy.valid_days × 24시간',
    ADD CONSTRAINT fk_coupon_policy FOREIGN KEY (coupon_policy_id) REFERENCES coupon_policy (id),
    ADD KEY ix_coupon_customer_policy (customer_id, coupon_policy_id, downloaded_at) COMMENT '30일 제한 계산(행사 단위)';
