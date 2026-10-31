-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다.

-- 권한 매트릭스를 ★ 데이터로 옮긴다 (디랩 1029 8-2)
--
-- 디랩: *「지금처럼 두어도 될 것 같습니다. ★ 권한 매트릭스 화면에서의 수정이 가능할까요?」*
--
-- ★ 지금은 값이 ★ 코드에 있다 — RoleMenuDefaults.BY_MENU.
--   그 클래스 주석도 *「매트릭스가 확정되면 … 값은 데이터로 옮긴다」* 였다.
--
-- ★★ 「표에 없는 메뉴는 ★ 전 역할에 보인다」 규칙을 ★ 그대로 유지한다.
--    ⚠️ 새 메뉴가 ★ 조용히 사라지면 ★ 기능이 없는 줄 안다(클래스 주석 그대로).
--    → 그래서 ★ 이 테이블에 행이 없는 메뉴는 ★ 전 역할에 보인다.
--
-- ⚠️ 아래 행은 ★ BY_MENU 에서 ★ 기계로 뽑았다 — 40개 메뉴 · 118행.
--    ★ 손으로 옮기지 않았다. 옮기면 한 칸이 틀려도 ★ 화면에서만 드러난다.
--
-- ⚠️⚠️ ★ 처음 뽑을 때 ★ 두 개(notice·qna)를 놓쳤다 — 그 둘만 ★ EnumSet.of 가
--    ★ 여러 줄에 걸쳐 있어 정규식이 못 잡았다. ★ 테스트가 잡았다
--    (★ 「테이블이 BY_MENU 와 같다」). ★ 기계로 뽑아도 ★ 대조는 필요하다.
--
-- ★ menu 를 ★ code 로 JOIN 한다 — id 는 환경마다 다르다.
--
-- ⚠️ 계정별 지정(account_menu)이 있으면 ★ 그쪽이 이긴다 — 우선순위는 그대로다.
--    화면에 「계정별로 따로 지정한 계정에는 적용되지 않습니다」를 적을 것.
--
-- ★ 메뉴는 ★ 권한이 아니다 — 표에서 메뉴를 켜도 ★ 서버 @PreAuthorize 가 막으면 안 된다.
--   화면에 「메뉴 표시 설정이며, 기능 권한은 역할에 따라 정해집니다」를 적을 것.

CREATE TABLE role_menu_default (
    id          BIGSERIAL PRIMARY KEY,
    role        VARCHAR(20) NOT NULL,
    menu_id     BIGINT      NOT NULL REFERENCES menu (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT role_menu_default_role_check
        CHECK (role IN ('SUPER_ADMIN', 'BRANCH_ADMIN', 'TEACHER', 'STAFF', 'READONLY')),
    CONSTRAINT uq_role_menu_default UNIQUE (role, menu_id)
);

COMMENT ON TABLE role_menu_default IS
    '역할별로 기본으로 보이는 메뉴. 행이 없는 메뉴는 전 역할에 보인다 — 새 메뉴가 '
    '조용히 사라지지 않게. 계정별 지정(account_menu)이 있으면 그쪽이 이긴다.';

-- ★ 「어느 메뉴에 설정이 있는가」를 아는 것이 ★ 「전 역할에 보인다」 판정의 근거다.
--   ⚠️ 행이 0개인 것과 ★ 「아무 역할에도 안 보인다」는 ★ 다른 뜻이다 —
--   후자를 표현하려면 ★ 그 메뉴를 가진 행이 하나라도 있어야 한다.
CREATE INDEX idx_role_menu_default_menu ON role_menu_default (menu_id)
 WHERE is_deleted = FALSE;

INSERT INTO role_menu_default (role, menu_id, created_by)
SELECT v.role, m.id, 0
  FROM (VALUES
            ('SUPER_ADMIN', 'student'),
            ('BRANCH_ADMIN', 'student'),
            ('TEACHER', 'student'),
            ('STAFF', 'student'),
            ('READONLY', 'student'),
            ('SUPER_ADMIN', 'attendance'),
            ('BRANCH_ADMIN', 'attendance'),
            ('TEACHER', 'attendance'),
            ('STAFF', 'attendance'),
            ('READONLY', 'attendance'),
            ('SUPER_ADMIN', 'statistics'),
            ('BRANCH_ADMIN', 'statistics'),
            ('TEACHER', 'statistics'),
            ('STAFF', 'statistics'),
            ('READONLY', 'statistics'),
            ('SUPER_ADMIN', 'grade'),
            ('BRANCH_ADMIN', 'grade'),
            ('TEACHER', 'grade'),
            ('SUPER_ADMIN', 'class'),
            ('BRANCH_ADMIN', 'class'),
            ('TEACHER', 'class'),
            ('SUPER_ADMIN', 'penalty'),
            ('BRANCH_ADMIN', 'penalty'),
            ('TEACHER', 'penalty'),
            ('SUPER_ADMIN', 'absence'),
            ('BRANCH_ADMIN', 'absence'),
            ('TEACHER', 'absence'),
            ('SUPER_ADMIN', 'approval'),
            ('BRANCH_ADMIN', 'approval'),
            ('TEACHER', 'approval'),
            ('SUPER_ADMIN', 'firewall'),
            ('BRANCH_ADMIN', 'firewall'),
            ('TEACHER', 'firewall'),
            ('SUPER_ADMIN', 'schedule'),
            ('BRANCH_ADMIN', 'schedule'),
            ('TEACHER', 'schedule'),
            ('SUPER_ADMIN', 'consult'),
            ('BRANCH_ADMIN', 'consult'),
            ('TEACHER', 'consult'),
            ('SUPER_ADMIN', 'routine'),
            ('BRANCH_ADMIN', 'routine'),
            ('TEACHER', 'routine'),
            ('SUPER_ADMIN', 'learning-plan'),
            ('BRANCH_ADMIN', 'learning-plan'),
            ('TEACHER', 'learning-plan'),
            ('SUPER_ADMIN', 'personal-record'),
            ('BRANCH_ADMIN', 'personal-record'),
            ('TEACHER', 'personal-record'),
            ('SUPER_ADMIN', 'timetable'),
            ('BRANCH_ADMIN', 'timetable'),
            ('TEACHER', 'timetable'),
            ('SUPER_ADMIN', 'patrol'),
            ('BRANCH_ADMIN', 'patrol'),
            ('TEACHER', 'patrol'),
            ('SUPER_ADMIN', 'survey'),
            ('BRANCH_ADMIN', 'survey'),
            ('TEACHER', 'survey'),
            ('SUPER_ADMIN', 'lecture'),
            ('BRANCH_ADMIN', 'lecture'),
            ('SUPER_ADMIN', 'notice'),
            ('BRANCH_ADMIN', 'notice'),
            ('TEACHER', 'notice'),
            ('STAFF', 'notice'),
            ('SUPER_ADMIN', 'qna'),
            ('BRANCH_ADMIN', 'qna'),
            ('TEACHER', 'qna'),
            ('STAFF', 'qna'),
            ('SUPER_ADMIN', 'billing'),
            ('BRANCH_ADMIN', 'billing'),
            ('STAFF', 'billing'),
            ('READONLY', 'billing'),
            ('SUPER_ADMIN', 'meal'),
            ('BRANCH_ADMIN', 'meal'),
            ('STAFF', 'meal'),
            ('READONLY', 'meal'),
            ('SUPER_ADMIN', 'tuition'),
            ('BRANCH_ADMIN', 'tuition'),
            ('STAFF', 'tuition'),
            ('SUPER_ADMIN', 'payment-request'),
            ('BRANCH_ADMIN', 'payment-request'),
            ('STAFF', 'payment-request'),
            ('SUPER_ADMIN', 'cash-receipt'),
            ('BRANCH_ADMIN', 'cash-receipt'),
            ('STAFF', 'cash-receipt'),
            ('SUPER_ADMIN', 'meal-vendor'),
            ('BRANCH_ADMIN', 'meal-vendor'),
            ('STAFF', 'meal-vendor'),
            ('SUPER_ADMIN', 'notification'),
            ('BRANCH_ADMIN', 'notification'),
            ('STAFF', 'notification'),
            ('SUPER_ADMIN', 'seat'),
            ('BRANCH_ADMIN', 'seat'),
            ('STAFF', 'seat'),
            ('SUPER_ADMIN', 'waiting'),
            ('BRANCH_ADMIN', 'waiting'),
            ('STAFF', 'waiting'),
            ('SUPER_ADMIN', 'student-signup'),
            ('BRANCH_ADMIN', 'student-signup'),
            ('STAFF', 'student-signup'),
            ('SUPER_ADMIN', 'app-account'),
            ('BRANCH_ADMIN', 'app-account'),
            ('STAFF', 'app-account'),
            ('SUPER_ADMIN', 'master'),
            ('BRANCH_ADMIN', 'master'),
            ('SUPER_ADMIN', 'holiday'),
            ('BRANCH_ADMIN', 'holiday'),
            ('SUPER_ADMIN', 'period'),
            ('BRANCH_ADMIN', 'period'),
            ('SUPER_ADMIN', 'scholarship'),
            ('BRANCH_ADMIN', 'scholarship'),
            ('SUPER_ADMIN', 'staff'),
            ('BRANCH_ADMIN', 'staff'),
            ('SUPER_ADMIN', 'audit-log'),
            ('BRANCH_ADMIN', 'audit-log'),
            ('SUPER_ADMIN', 'app-config'),
            ('BRANCH_ADMIN', 'app-config'),
            ('SUPER_ADMIN', 'branch-config'),
            ('SUPER_ADMIN', 'pg-site')
       ) AS v(role, code)
  JOIN menu m ON m.code = v.code AND m.is_deleted = FALSE
 WHERE NOT EXISTS (
          SELECT 1 FROM role_menu_default d
           WHERE d.role = v.role AND d.menu_id = m.id
       );
