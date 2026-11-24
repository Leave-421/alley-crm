-- D1(#12) 설정값(NFR-04). 키와 기본값은 코드 SettingKey에 있고, 이 테이블은 권역별로 바꾼 값만 둔다.
-- 기준: 기획 저장소 docs/05-policy.md 「설정값」 · 「추천 설정값」.

CREATE TABLE setting (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    region_id   BIGINT       NOT NULL,
    setting_key VARCHAR(60)  NOT NULL,
    value       VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_setting_region_key UNIQUE (region_id, setting_key),
    CONSTRAINT fk_setting_region FOREIGN KEY (region_id) REFERENCES region (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
