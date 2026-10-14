-- 공지 대상자 — 「학생들을 개별 선택하여 발송」 (요구사항 1-5).
--
-- ★★ `INDIVIDUAL` 은 **학생 한 명**이라 이 요구를 못 담는다. 사람 수만큼 공지를 만들면:
--    ① 목록에 **같은 글이 N개** 떠서 담당자가 「내가 중복으로 보냈나」로 읽는다
--    ② 오타 하나를 고치려면 **N번 고쳐야** 하고, 하나라도 빠뜨리면
--       **사람마다 다른 공지**를 본 셈이 된다
--    ③ 「읽음 몇 명」 집계가 **N개로 쪼개진다**
--
-- ★ 그래서 **공지 하나에 대상자 목록**을 단다 — 설문 `survey_participant` 과 같은 모양이다.
--
-- ⚠️ 기존 `INDIVIDUAL` 은 **그대로 둔다.** 이미 그렇게 보낸 공지가 있을 수 있고,
--    CHECK 제약이 네 조합을 강제하고 있어 건드리면 그 데이터가 깨진다.
--    새 범위 `SELECTED` 를 더해 **대상자 테이블을 쓰는 쪽**을 가린다.

-- ⚠️ **기존 제약 이름이 `ck_notice_target` 이다.** 새 이름으로만 추가하면
--    옛 제약이 그대로 남아 `SELECTED` 를 막는다 — 실제로 그렇게 깨졌다.
ALTER TABLE notice DROP CONSTRAINT IF EXISTS ck_notice_target;
ALTER TABLE notice DROP CONSTRAINT IF EXISTS notice_scope_check;
ALTER TABLE notice DROP CONSTRAINT IF EXISTS notice_scope_target_check;

ALTER TABLE notice
    ADD CONSTRAINT notice_scope_check
        CHECK (scope IN ('ALL', 'BRANCH', 'CLASS', 'INDIVIDUAL', 'SELECTED'));

-- 범위마다 채워져야 하는 칸이 다르다. 잘못 조합하면 **조회가 조용히 비므로** DB가 막는다
ALTER TABLE notice
    ADD CONSTRAINT notice_scope_target_check CHECK (
        (scope = 'ALL'        AND academy_id IS NULL     AND class_master_id IS NULL AND enrollment_id IS NULL)
     OR (scope = 'BRANCH'     AND academy_id IS NOT NULL AND class_master_id IS NULL AND enrollment_id IS NULL)
     OR (scope = 'CLASS'      AND academy_id IS NOT NULL AND class_master_id IS NOT NULL AND enrollment_id IS NULL)
     OR (scope = 'INDIVIDUAL' AND academy_id IS NOT NULL AND class_master_id IS NULL AND enrollment_id IS NOT NULL)
     -- ★ SELECTED 는 대상자가 **별도 테이블**에 있다. 여기 칸은 전부 빈다
     OR (scope = 'SELECTED'   AND academy_id IS NOT NULL AND class_master_id IS NULL AND enrollment_id IS NULL)
    );

CREATE TABLE notice_target
(
    id            BIGSERIAL PRIMARY KEY,
    year          SMALLINT    NOT NULL,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    notice_id     BIGINT      NOT NULL REFERENCES notice (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 같은 학생을 두 번 넣어도 한 행이다. 중복 행이 있으면 **「읽음 몇 명」 집계가 틀어진다**
CREATE UNIQUE INDEX uq_notice_target
    ON notice_target (notice_id, enrollment_id) WHERE NOT is_deleted;

-- 앱 피드가 「내가 대상인 공지」를 찾는 경로다
CREATE INDEX idx_notice_target_enrollment
    ON notice_target (enrollment_id) WHERE NOT is_deleted;

COMMENT ON TABLE notice_target IS
    '공지 하나의 수신 대상 학생들. scope=SELECTED 일 때 쓴다 — 사람마다 공지를 만들면 수정·집계가 쪼개진다';
