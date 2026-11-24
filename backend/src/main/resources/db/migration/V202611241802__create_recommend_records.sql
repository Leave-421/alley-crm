-- D19(#120) DB 기반 2: 추천 기록 · 공공데이터
-- 기준: 기획 저장소 docs/06-data.md 「추천 기록」 · 「알림 · 설정 · 공공데이터」.
-- 지도 추천 노출 · 오늘의 코스는 탐색 기록이고, 소개 판정 · 수락률은 course_proposal만 쓴다(11-24 (2)).

CREATE TABLE store_click (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    visitor_id  BIGINT       NOT NULL,
    store_id    BIGINT       NOT NULL,
    clicked_at  DATETIME(6)  NOT NULL,
    data_source VARCHAR(10)  NOT NULL DEFAULT 'real',
    PRIMARY KEY (id),
    CONSTRAINT fk_store_click_visitor FOREIGN KEY (visitor_id) REFERENCES visitor (id),
    CONSTRAINT fk_store_click_store FOREIGN KEY (store_id) REFERENCES store (id),
    KEY ix_store_click_store_time (store_id, clicked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE recommendation_exposure (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    visitor_id            BIGINT       NOT NULL,
    base_store_id         BIGINT       NOT NULL,
    recommended_store_id  BIGINT       NOT NULL,
    `rank`                INT          NOT NULL,
    page                  INT          NOT NULL DEFAULT 0 COMMENT '리롤 몇 번째',
    badge                 VARCHAR(20)  NOT NULL COMMENT 'visit_data|local_popular',
    reason_type           VARCHAR(30)  NULL     COMMENT '이유 문구 종류(E에서 정함)',
    shown_at              DATETIME(6)  NOT NULL,
    data_source           VARCHAR(10)  NOT NULL DEFAULT 'real',
    PRIMARY KEY (id),
    CONSTRAINT fk_exposure_visitor FOREIGN KEY (visitor_id) REFERENCES visitor (id),
    CONSTRAINT fk_exposure_base_store FOREIGN KEY (base_store_id) REFERENCES store (id),
    CONSTRAINT fk_exposure_recommended_store FOREIGN KEY (recommended_store_id) REFERENCES store (id),
    KEY ix_exposure_visitor (visitor_id, shown_at),
    KEY ix_exposure_base_time (base_store_id, shown_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE course_proposal (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    base_reservation_id   BIGINT       NOT NULL,
    recommended_store_id  BIGINT       NOT NULL,
    first_shown_at        DATETIME(6)  NOT NULL COMMENT '리롤로 다시 표시돼도 새로 만들지 않음',
    data_source           VARCHAR(10)  NOT NULL DEFAULT 'real',
    PRIMARY KEY (id),
    CONSTRAINT uk_course_proposal UNIQUE (base_reservation_id, recommended_store_id) COMMENT '수락률 분모: 조합당 1건',
    CONSTRAINT fk_course_proposal_reservation FOREIGN KEY (base_reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_course_proposal_store FOREIGN KEY (recommended_store_id) REFERENCES store (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE reservation
    ADD CONSTRAINT fk_reservation_course_proposal FOREIGN KEY (course_proposal_id) REFERENCES course_proposal (id);

CREATE TABLE recommendation_feedback (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    exposure_id  BIGINT       NOT NULL,
    type         VARCHAR(10)  NOT NULL COMMENT 'reject|reroll (사유는 받지 않음)',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_feedback_exposure FOREIGN KEY (exposure_id) REFERENCES recommendation_exposure (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE course_preview_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    visitor_id  BIGINT       NOT NULL,
    type        VARCHAR(12)  NOT NULL COMMENT 'impression|click',
    store_ids   JSON         NOT NULL COMMENT '코스의 가게 3곳(순서대로)',
    day_type    VARCHAR(10)  NOT NULL COMMENT 'weekday|weekend',
    time_band   VARCHAR(20)  NOT NULL COMMENT '시간대 구간(E2에서 값 정함)',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    data_source VARCHAR(10)  NOT NULL DEFAULT 'real',
    PRIMARY KEY (id),
    CONSTRAINT fk_preview_visitor FOREIGN KEY (visitor_id) REFERENCES visitor (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 업종 단위 값(가게 단위 아님). kind · slot 값은 E2에서 정한다.
CREATE TABLE public_stat (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    kind           VARCHAR(30)   NOT NULL COMMENT '매력도 · CLQ · 시간대 · 성별·연령대 비중',
    category_from  VARCHAR(20)   NULL,
    category_to    VARCHAR(20)   NULL,
    slot           VARCHAR(20)   NULL     COMMENT '시간대(요일 포함)',
    gender         VARCHAR(10)   NULL,
    age_group      VARCHAR(20)   NULL,
    value          DECIMAL(12, 6) NOT NULL,
    source         VARCHAR(200)  NOT NULL COMMENT '출처(화면에 표시)',
    period         VARCHAR(50)   NOT NULL COMMENT '기준 기간(화면에 표시)',
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_public_stat_kind (kind, category_from, category_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
