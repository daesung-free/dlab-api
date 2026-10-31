-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다 — 14자리 파일이 만든
--   테이블(penalty_rule)을 참조하므로 순서가 중요하다.

-- 자동 지각 ↔ 사유 지각·사유 결석 ★ 중복 방지 (디랩 1029 4-2)
--
-- ★★ 시트 원문: *「자동 지각(-1)이 적용된 후 직원이 사유를 파악하여 사유지각(-1)을
--    입력하거나 사유결석(-2)을 입력하는 경우 ★ 벌점이 중복 적용
--    (자동지각+사유지각 / 자동지각+사유결석)이 되지 않아야 합니다」*
--
-- ★ 왜 생기는가 — 사유지각은 ★ 원장이 LATE 다.
--    KioskAttendanceService 주석: *「사유지각은 화면엔 등원으로 뜨지만 ★ 원장은
--    지각이고, 벌점은 원장을 따른다」*. 그래서 「A → 자동 지각」 규칙을 켜면
--    ★ 사유지각 학생에게도 -1 이 붙고, ★ 승인 트리거가 -1 을 또 붙인다.
--
-- ★★ 대체 관계를 ★ 데이터로 둔다 — ⚠️ 코드에 「LATE 면 자동 지각을 지운다」를
--    박지 말 것. ★ 디랩이 항목 이름을 바꾸면 깨진다(1029 4-2 지침).
--
-- ★ 자기 참조다. 「이 규칙이 부여할 때 ★ 어느 규칙의 자동 부여분을 먼저 거둘지」를
--   가리킨다. NULL 이면 ★ 거두지 않는다(기본) — 대부분의 규칙이 그렇다.

ALTER TABLE penalty_rule
    ADD COLUMN IF NOT EXISTS supersedes_rule_id BIGINT;

ALTER TABLE penalty_rule
    ADD CONSTRAINT fk_penalty_rule_supersedes
        FOREIGN KEY (supersedes_rule_id) REFERENCES penalty_rule (id);

-- ⚠️ 자기 자신을 가리키면 ★ 부여하기 전에 자기 것을 거두는 모순이 된다
ALTER TABLE penalty_rule
    ADD CONSTRAINT penalty_rule_supersedes_not_self
        CHECK (supersedes_rule_id IS NULL OR supersedes_rule_id <> id);

COMMENT ON COLUMN penalty_rule.supersedes_rule_id IS
    '이 규칙이 부여할 때 먼저 거둘 규칙. 자동 지각 ↔ 사유 지각 중복 방지용(1029 4-2). '
    '거두는 대상은 자동 부여분(멱등키 있는 행)만이고 수기 부여분은 건드리지 않는다. '
    'NULL 이면 거두지 않는다 — 기본값이다.';
