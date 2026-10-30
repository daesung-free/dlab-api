-- 사유 신청 승인 트리거 (디랩 1029 답변서 4-1)
--
-- 사유 결석·지각·조퇴·외출 4종이 「직원이 사유 확인 후 승인 시」 자동 부여로 확정됐다.
-- 그동안 벌점은 태깅에만 붙었다 — approval 도메인에 엔진 호출이 0건이었다.
--
-- ★ V1 이 인라인 CHECK 로 만들어 기본 이름(penalty_rule_trigger_type_check)을 갖는다.
--   DROP IF EXISTS 없이 ADD 하면 제약이 둘이 되어 새 값이 계속 거부된다.
--   V20260812_1000 이 REGULAR_SCHEDULE 을 더할 때 같은 방식으로 했다.
ALTER TABLE penalty_rule DROP CONSTRAINT IF EXISTS penalty_rule_trigger_type_check;

ALTER TABLE penalty_rule
    ADD CONSTRAINT penalty_rule_trigger_type_check
    CHECK (trigger_type IN ('ATTENDANCE', 'DAILY_ROUTINE', 'REGULAR_SCHEDULE', 'APPROVAL'));

COMMENT ON COLUMN penalty_rule.trigger_type IS
    '무엇이 이 규칙을 건드리나. APPROVAL 은 사유 신청 승인 — 조건값이 AbsenceReasonType 이름이다';

-- ⚠️ 규칙 행은 넣지 않는다. 디랩 상벌점 항목 56개(원본 엑셀)를 받은 뒤 등록하고,
--    penalty_rule.active 가 기본 꺼짐이라 켜기 전엔 아무 일도 일어나지 않는다.
