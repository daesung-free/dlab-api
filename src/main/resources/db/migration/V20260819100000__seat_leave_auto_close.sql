-- V20260819100000: 좌석이탈 자동 마감 구분값 추가 (F-4.3-2)
--
-- 키오스크가 매일 00:30에 미복귀 상태인 이탈 건을 일괄 복귀 처리한다. 이건 학생이
-- 실제로 돌아온 것이 아니라 **하루 경계 정리**다.
--
-- ★ 이걸 RETURN으로 받으면 안 된다. "23시 이탈 → 00:30 복귀"로 기록되어
--   **정작 잡아야 할 장시간 미복귀가 복귀로 닫힌다.** 미복귀 감지가 이 행을 보고
--   "돌아왔네" 하고 넘어가면 감지 자체가 무의미해진다.
--
-- ★ 그렇다고 안 받으면 우리 DB에 복귀 없는 이탈이 무한정 열린 채로 남고, 우리가 따로
--   마감 규칙을 만들어야 한다 — 두 시스템이 각자 마감하면 규칙이 갈린다.
--
-- ★ RETURN + 플래그가 아니라 별도 값인 이유: 값이 다르면 어느 코드 경로에서든
--   실수로 복귀로 집계될 수 없다. 플래그는 조건 하나만 빠뜨려도 조용히 섞인다.

-- 인라인 CHECK는 <테이블>_<컬럼>_check 로 이름이 붙는다
ALTER TABLE seat_leave_log DROP CONSTRAINT IF EXISTS seat_leave_log_event_type_check;

ALTER TABLE seat_leave_log ADD CONSTRAINT seat_leave_log_event_type_check
    CHECK (event_type IN ('LEAVE', 'RETURN', 'AUTO_CLOSE'));

COMMENT ON COLUMN seat_leave_log.event_type IS
    'LEAVE 이탈 / RETURN 실제 복귀 / AUTO_CLOSE 키오스크 00:30 일괄 마감(복귀 아님). '
    '미복귀 판정에서 AUTO_CLOSE를 복귀로 세면 안 된다';
