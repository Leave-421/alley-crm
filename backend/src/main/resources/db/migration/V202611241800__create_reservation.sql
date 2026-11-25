-- D19(#120) DB 기반 2: 예약
-- 기준: 기획 저장소 docs/06-data.md 「예약」, enum 값은 docs/CONTRACTS.md 「3」.
-- course_proposal_id의 외래키는 course_proposal 테이블을 만든 뒤(V202611241802) 건다.

CREATE TABLE reservation (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_no      VARCHAR(20)  NOT NULL COMMENT '비회원 조회용 예약번호',
    store_id            BIGINT       NOT NULL,
    customer_id         BIGINT       NOT NULL,
    start_at            DATETIME(6)  NOT NULL,
    party_size          INT          NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'requested' COMMENT 'requested|confirmed|visited|canceled',
    cancel_reason       VARCHAR(30)  NULL     COMMENT 'customer_canceled|store_rejected|auto_canceled',
    requested_at        DATETIME(6)  NOT NULL COMMENT '손님 신청 시각(소개 마감 · 쿠폰 7일 기준)',
    confirmed_at        DATETIME(6)  NULL,
    visited_at          DATETIME(6)  NULL,
    canceled_at         DATETIME(6)  NULL,
    pending_start_at    DATETIME(6)  NULL     COMMENT '변경 신청 중일 때 바꿀 값',
    pending_party_size  INT          NULL,
    deposit_amount      INT          NOT NULL DEFAULT 0,
    refund_amount       INT          NULL,
    visit_amount        INT          NULL     COMMENT '방문 처리 때 결제 금액(쿠폰 할인 전)',
    delay_eta           DATETIME(6)  NULL     COMMENT '지연 입력 도착 시간, 비회원 파기 대상',
    request_note        VARCHAR(200) NULL     COMMENT '손님 요청 사항',
    course_root_id      BIGINT       NULL     COMMENT '다음 코스 예약만: 기준 가게 예약 id',
    course_proposal_id  BIGINT       NULL     COMMENT '소개 후보만: 사용한 정식 코스 제안 id',
    sender_store_id     BIGINT       NULL     COMMENT '보낸 가게 = course_root_id 예약의 가게',
    data_source         VARCHAR(10)  NOT NULL DEFAULT 'real' COMMENT 'real|demo|public',
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_no UNIQUE (reservation_no),
    CONSTRAINT fk_reservation_store FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT fk_reservation_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_reservation_course_root FOREIGN KEY (course_root_id) REFERENCES reservation (id),
    CONSTRAINT fk_reservation_sender_store FOREIGN KEY (sender_store_id) REFERENCES store (id),
    CONSTRAINT ck_reservation_party CHECK (party_size > 0),
    CONSTRAINT ck_reservation_amounts CHECK (deposit_amount >= 0 AND (refund_amount IS NULL OR refund_amount >= 0)
        AND (visit_amount IS NULL OR visit_amount >= 0)),
    KEY ix_reservation_store_start (store_id, start_at),
    KEY ix_reservation_customer (customer_id),
    KEY ix_reservation_status_start (status, start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
