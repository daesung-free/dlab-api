-- ⚠️ 파일명이 14자리인 이유 — 밑줄 방식으로 지으면 깨진다.
--
--   V20261023_0900  → Flyway 가 20261023.0900 으로 파싱한다
--   V20261022230000 → 20261022230000
--
-- ★ 밑줄 쪽이 **훨씬 작아서 먼저 실행된다.** 이 파일의 UPDATE 는 V20261022230000 이
--   INSERT 한 행(MEAL_APPLY_DEADLINE 등)을 고치는데, 먼저 돌면 **0건을 먹고 조용히
--   넘어간다.** 컬럼은 생기고 값만 안 바뀌어 「왜 아직 막혀 있지」가 된다.
--   CLAUDE.md §7 의 명시적 예외에 해당한다(14자리가 만든 것을 참조하면 14자리로 짓는다).

-- 공통 안내는 학생명을 요구하지 않는다 (2026-10-22 결정).
--
-- ★★ 「모든 템플릿에 학생명 필수」 규칙에 축을 하나 더한다.
--
-- 규칙의 근거는 **다자녀 학부모가 「이거 누구 얘기지」 헷갈리지 않게** 하는 것이다
-- (CLAUDE.md 알림 템플릿 공통 원칙). 그런데 디랩이 준 문구 중 둘은 **학생 개인에 관한
-- 통지가 아니라 전원 공통 안내**다.
--
--     급식 마감    [DLab] {월} 급식 신청이 {날짜}에 마감됩니다.
--     등록비 안내   [DLab] {기간} {월} 등록기간입니다.
--
-- 마감일과 등록기간은 **학생마다 다르지 않다.** 누구 얘기인지 헷갈릴 일이 없고, 넣으면
-- 문장이 어색해진다. ⚠️ 규칙을 없애는 것이 아니라 **적용 범위를 제 뜻대로 좁히는 것**이다.
--
-- ⚠️ 이 두 건은 그동안 content_confirmed 를 올릴 수 없어 sendable = false 였다 —
--    문구를 등록해도 보낼 수 없는 상태였다.
--
-- ★ 그 대신 수신자 중복을 제거한다 — 자녀가 둘이면 같은 안내가 두 번 가기 때문이다.
--   그쪽은 코드에서 처리한다(ManualNotificationService).

ALTER TABLE notification_template
    ADD COLUMN student_specific BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN notification_template.student_specific IS
    '학생 개인에 관한 알림인가. TRUE 면 본문에 {studentName} 자리가 있어야 문구를 확정할 수 있다. '
    'FALSE 는 전원 공통 안내(급식 마감·등록기간)이고, 수신자 중복도 제거한다.';

-- ── 공통 안내 두 건 ────────────────────────────────────────────
--
-- ★ 조건을 event_code 로만 걸지 않고 student_specific 이 기본값인 것까지 본다 —
--   누군가 이미 손으로 바꿔 둔 행을 되돌리지 않기 위해서다.

UPDATE notification_template
   SET student_specific = FALSE
 WHERE event_code IN ('MEAL_APPLY_DEADLINE', 'TUITION_REGISTRATION_NOTICE')
   AND student_specific = TRUE;

-- 이제 문구 확정 조건을 만족한다. ⚠️ 심사(review_status)는 그대로 둔다 —
-- 카카오 심사는 별개 축이고, 여기서 올리면 「승인됨」으로 보이는데 발송이 거절된다.
UPDATE notification_template
   SET content_confirmed = TRUE
 WHERE event_code IN ('MEAL_APPLY_DEADLINE', 'TUITION_REGISTRATION_NOTICE')
   AND content_confirmed = FALSE
   AND body_template <> '';
