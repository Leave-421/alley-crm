-- C23(#165): visit_report.status VARCHAR(20)이 계약 값 'owner_check_requested'(21자)보다 짧아 넓힌다.
-- 값 목록(ck_visit_report_status)과 기본값은 그대로다. D24(V202611261000)에서 만든 칸.
ALTER TABLE visit_report
    MODIFY status VARCHAR(30) NOT NULL DEFAULT 'received' COMMENT 'received|owner_check_requested|done';
