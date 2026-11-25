-- 예약 가능 기간: 기본 30일, 사장님은 1 ~ 60일 중에서 정한다.
-- 기준: 기획 저장소 docs/reviews/decision-meeting-2026-11-25-2.md. (D1의 기본 7일은 임시값이었다)

UPDATE store SET booking_window_days = 30 WHERE booking_window_days = 7;

ALTER TABLE store
    MODIFY booking_window_days INT NOT NULL DEFAULT 30 COMMENT '예약 가능 기간(일, 1~60). 오늘부터 이 일수 뒤 날짜까지',
    ADD CONSTRAINT ck_store_booking_window CHECK (booking_window_days BETWEEN 1 AND 60);
