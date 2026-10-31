-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다.

-- 취침(졸음) 자동 벌점 — ★ PATROL 트리거 + ★ 임계값 칸 (디랩 1029 4-6)
--
-- 시트 원문: *「교시별로 학생 취침을 ★ 3회 체크하면 자동 벌점 부여」*
--
-- ★★ 낡은 주석이 이것을 막고 있었다.
--
--    PenaltyTriggerType 주석: *「PATROL_SLEEP_2(2회 연속 졸음)를 넣지 말 것 —
--    사감 순찰이 0803에 전면 폐기되면서 이 트리거도 삭제됐다」*
--    → ★ 순찰은 ★ 10/13 에 다시 들어왔고(피드백 1-3), 디랩이 이번에 ★ 졸음 벌점을
--      명시적으로 요청했다. ★ 그 주석은 낡았다.
--
-- ── ★★ 임계값을 칸으로 둔다 ────────────────────────────────────
--
-- 1029 지침: *「「3회」는 규칙의 조건값이나 ★ 임계값 칸으로 두십시오.
--            ★ 코드에 3을 박지 말 것」*
--
-- ★ 조건값에 넣으면(SLEEP_3) ★ 3을 2로 바꾸려면 ★ 규칙을 새로 만들어야 한다 —
--   그러면 그 전에 부여된 벌점의 근거(규칙 id)가 ★ 다른 규칙을 가리킨다.
-- ★ 칸으로 두면 ★ 행만 고친다.
--
-- ⚠️ NULL 이면 ★ 임계값이 없다는 뜻이다 — 지금 트리거 대부분이 그렇다
--    (건별로 걸리고 세지 않는다).

ALTER TABLE penalty_rule
    ADD COLUMN IF NOT EXISTS threshold SMALLINT;

ALTER TABLE penalty_rule
    ADD CONSTRAINT penalty_rule_threshold_check
        CHECK (threshold IS NULL OR threshold >= 1);

COMMENT ON COLUMN penalty_rule.threshold IS
    '몇 번째에 부여하는가. 취침 3회처럼 횟수를 세는 규칙이 쓴다(1029 4-6). '
    'NULL 은 세지 않고 건별로 걸린다는 뜻이다 — 지금 트리거 대부분이 그렇다.';

-- ── PATROL 트리거 ──────────────────────────────────────────────

ALTER TABLE penalty_rule
    DROP CONSTRAINT IF EXISTS penalty_rule_trigger_type_check;

ALTER TABLE penalty_rule
    ADD CONSTRAINT penalty_rule_trigger_type_check
        CHECK (trigger_type IN ('ATTENDANCE', 'DAILY_ROUTINE', 'APPROVAL',
                                'REGULAR_SCHEDULE', 'PATROL'));

-- ⚠️⚠️ ★ REGULAR_SCHEDULE 을 ★ 처음에 빼먹었다 — ★ 테스트가 잡았다
--    (「정기일정 미인정도 트리거로 쓸 수 있다」).
--    ★ CHECK 를 다시 쓸 때는 ★ 기존 값을 전부 옮겨야 한다 —
--    ⚠️ enum 을 보고 쓰지 않고 ★ 앞 마이그레이션을 보고 쓰면 그 사이에 늘어난 값이 빠진다.
