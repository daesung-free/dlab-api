-- V20260806_1720: 교시 유니크가 soft delete를 제외하도록 고친다
--
-- ★ 문제 — 지운 교시 번호를 다시 쓸 수 없었다.
--   uq_period_master(academy_id, year, day_type, period_no)가 테이블 제약이라
--   is_deleted = TRUE인 행까지 센다. 교시 편집은 "3교시를 지우고 다시 만든다"가
--   일상적인 조작인데, 지운 순간 그 번호가 영구히 막혔다.
--   행을 물리 삭제하면 지난 출결이 어느 교시 구성으로 판정됐는지 추적이 끊기므로
--   soft delete를 유지하고 제약 쪽을 고친다.
--
--   다른 테이블(meal_application·app_config·push_token)이 이미 쓰는 방식과 같다.

ALTER TABLE period_master DROP CONSTRAINT uq_period_master;

CREATE UNIQUE INDEX uq_period_master
    ON period_master (academy_id, year, day_type, period_no)
    WHERE is_deleted = FALSE;

COMMENT ON INDEX uq_period_master IS
    '살아 있는 교시만 번호 중복을 막는다. 삭제분까지 세면 지운 번호를 다시 못 쓴다';
