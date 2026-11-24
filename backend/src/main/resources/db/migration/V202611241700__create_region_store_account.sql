-- D1(#12) DB 기반 1: 권역 · 가게 · 계정
-- 기준: 기획 저장소 docs/06-data.md 「권역 · 가게 · 계정」, enum 값은 docs/CONTRACTS.md 「3. Entity · 상태」.
-- 테이블 · 칸 변경은 담당 D에게 요청한다(00-backlog 「같이 쓰는 것 규칙」).

CREATE TABLE region (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(50)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    region_id                   BIGINT        NOT NULL,
    name                        VARCHAR(50)   NOT NULL,
    category                    VARCHAR(20)   NOT NULL COMMENT 'restaurant|cafe|dessert|pub|workshop|bookstore|gift_shop|stationery',
    alley                       VARCHAR(20)   NOT NULL COMMENT 'front_gate|center|back_street',
    map_x                       DECIMAL(7, 4) NULL     COMMENT '골목 안 표시 좌표',
    map_y                       DECIMAL(7, 4) NULL,
    real_lat                    DECIMAL(9, 6) NULL     COMMENT '실제 좌표(검증된 경우만)',
    real_lng                    DECIMAL(9, 6) NULL,
    real_verified               BOOLEAN       NOT NULL DEFAULT FALSE,
    address                     VARCHAR(200)  NULL     COMMENT '상인회가 관리',
    join_status                 VARCHAR(20)   NOT NULL DEFAULT 'requested' COMMENT 'requested|approved|rejected|withdraw_requested|withdrawn',
    business_status             VARCHAR(20)   NOT NULL DEFAULT 'open' COMMENT 'open|paused|closed (사장님이 바꿈)',
    business_status_changed_at  DATETIME(6)   NULL,
    reservation_enabled         BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '예약 받기: 받는 중 / 잠시 멈춤',
    open_time                   TIME          NULL     COMMENT '영업 시작(필수 — 가입 승인 전에는 비어 있을 수 있음)',
    close_time                  TIME          NULL,
    closed_days                 VARCHAR(40)   NOT NULL DEFAULT '' COMMENT '휴무 요일 mon,tue,... 빈 값이면 연중무휴',
    slot_minutes                INT           NOT NULL DEFAULT 30,
    capacity_per_slot           INT           NOT NULL DEFAULT 1,
    booking_window_days         INT           NOT NULL DEFAULT 7,
    cancel_deadline_hours       INT           NOT NULL DEFAULT 24 COMMENT '24 이하만(예약 시각 24시간 전보다 이르게 못 잡음)',
    deposit_per_person          INT           NOT NULL DEFAULT 0 COMMENT '원, 0 가능',
    max_party_size              INT           NULL     COMMENT '한 팀 최대 인원, 비면 제한 없음',
    late_cancel_refund_rate     DECIMAL(4, 3) NULL     COMMENT '비면 권역 설정값(late_cancel_refund_rate)',
    intro                       VARCHAR(100)  NULL     COMMENT '여유',
    price_range                 VARCHAR(50)   NULL     COMMENT '여유',
    menu_items                  JSON          NULL     COMMENT '여유: [{"name","price"}]',
    amenities                   JSON          NULL     COMMENT '여유: ["outdoor_seating",...]',
    seats                       INT           NULL     COMMENT '여유',
    photos                      JSON          NULL     COMMENT '여유: 최대 3장 URL',
    created_at                  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_store_region FOREIGN KEY (region_id) REFERENCES region (id),
    CONSTRAINT ck_store_cancel_deadline CHECK (cancel_deadline_hours BETWEEN 0 AND 24),
    CONSTRAINT ck_store_refund_rate CHECK (late_cancel_refund_rate IS NULL OR late_cancel_refund_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_store_deposit CHECK (deposit_per_person >= 0),
    KEY ix_store_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE account (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    login_id       VARCHAR(30)  NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL COMMENT 'customer|owner|association',
    store_id       BIGINT       NULL     COMMENT 'owner만, 가게당 1개',
    region_id      BIGINT       NULL     COMMENT 'association만, 권역당 1개',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_account_login_id UNIQUE (login_id),
    CONSTRAINT uk_account_store UNIQUE (store_id),
    CONSTRAINT uk_account_region UNIQUE (region_id),
    CONSTRAINT fk_account_store FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT fk_account_region FOREIGN KEY (region_id) REFERENCES region (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
