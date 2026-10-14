-- 역할 확인용 계정 2개 (담임 · 조회 전용). 전부 멱등이라 여러 번 돌려도 된다.
--
-- ★★ **역할을 섞지 않는다.** 화면이 `teacherOnly`·`readOnly` 를
--    **「그 역할만 가졌는가」**로 판정한다 — 관리자 역할이 하나라도 섞이면
--    그쪽 권한으로 쓸 수 있어 **막히는지 확인이 안 된다.**
--
-- ★ `must_change_password = FALSE` 다. 켜져 있으면 로그인 직후 비밀번호 변경에 막혀
--   화면 점검이 시작되지 않는다(`PasswordChangeRequiredInterceptor` 가 다른 API 를 전면 차단한다).
--
-- 비밀번호는 둘 다 `dlab1234!` — 기존 개발 계정과 같은 해시다.

-- ── 담임: teacher1 / dlab1234! (TEACHER 만, 분당) ────────────────
--
-- ★ **반을 하나 붙인다.** 담임 범위 판정이 「내 반 학생인가」로 갈리는 자리가 있어,
--   반이 없으면 빈 화면이 **「권한이 없다」인지 「데이터가 없다」인지 구분되지 않는다.**
-- ⚠️ `teacher` 에는 `year` 컬럼이 없다(지점에 직접 붙는다 — 겸직이 없어 배정 테이블도 없다, §2).
--    이름은 다른 시드의 「김담임」과 겹치므로 전용 이름을 쓴다
INSERT INTO teacher (academy_id, name, phone)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), '권한확인담임', '010-7777-0001'
WHERE NOT EXISTS (SELECT 1 FROM teacher WHERE phone = '010-7777-0001');

INSERT INTO account (account_type, login_id, password_hash, status, teacher_id,
                     must_change_password, password_changed_at)
SELECT 'TEACHER', 'teacher1',
       '$2b$10$weDEDd3JAASEyc5IKiZnfeErJcqgk3hdajGSDEb.PvihwrFuOAECK', 'ACTIVE', t.id, FALSE, now()
FROM teacher t WHERE t.phone = '010-7777-0001'
  AND NOT EXISTS (SELECT 1 FROM account WHERE login_id = 'teacher1');

INSERT INTO account_role (account_id, role_id)
SELECT a.id, r.id FROM account a, role r
WHERE a.login_id = 'teacher1' AND r.name = 'TEACHER'
  AND NOT EXISTS (SELECT 1 FROM account_role ar WHERE ar.account_id = a.id AND ar.role_id = r.id);

-- 그 담임에게 반을 하나 준다.
--
-- ★ **전용 반을 만든다.** 기존 반에 담임을 끼워 넣으려 했더니 분당의 올해 반이 이미
--   전부 담임이 지정돼 있어 `UPDATE 0` 이 됐다. 남의 반 담임을 덮으면 **그 반 담임 계정으로
--   하던 확인이 깨지므로**, 비는 반을 찾는 대신 이 계정 몫을 따로 만든다.
--
-- ⚠️ 학생을 배정하지는 않는다. 학번 채번(반별 순번)이 돌아 **다른 시드의 번호 순서가
--    밀린다** — 담임 범위 판정에는 반이 있는 것만으로 충분하고, 학생이 필요하면
--    화면에서 배정해 보면 된다.
INSERT INTO class_master (academy_id, year, name, class_type, homeroom_teacher_id)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'),
       EXTRACT(YEAR FROM CURRENT_DATE)::smallint,
       '권한확인반', 'FIXED',
       (SELECT id FROM teacher WHERE phone = '010-7777-0001')
WHERE NOT EXISTS (
    SELECT 1 FROM class_master
    WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31')
      AND year = EXTRACT(YEAR FROM CURRENT_DATE)::smallint
      AND name = '권한확인반');

-- ── 조회 전용: viewer1 / dlab1234! (READONLY 만, 분당) ───────────
--
-- ★ 2026-09-27 에 조회 전용 계정이 **임시 비밀번호를 받고도 비밀번호를 못 바꿔
--   영구히 잠긴** 일이 있었다. 원인은 화면이 쓰기 버튼을 통째로 막은 것이고
--   **서버는 `POST /admin/auth/password` 를 역할과 무관하게 열어 둔다**(`authenticated()`).
--   이 계정으로 그게 실제로 되는지 확인한다.
INSERT INTO employee (academy_id, name, dept_name, position_name, email, hired_date)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), '조회전용', '운영팀', '주임',
       'viewer@dlab.local', DATE '2026-01-02'
WHERE NOT EXISTS (SELECT 1 FROM employee WHERE email = 'viewer@dlab.local');

INSERT INTO account (account_type, login_id, password_hash, status, employee_id,
                     must_change_password, password_changed_at)
SELECT 'EMPLOYEE', 'viewer1',
       '$2b$10$weDEDd3JAASEyc5IKiZnfeErJcqgk3hdajGSDEb.PvihwrFuOAECK', 'ACTIVE', e.id, FALSE, now()
FROM employee e WHERE e.email = 'viewer@dlab.local'
  AND NOT EXISTS (SELECT 1 FROM account WHERE login_id = 'viewer1');

INSERT INTO account_role (account_id, role_id)
SELECT a.id, r.id FROM account a, role r
WHERE a.login_id = 'viewer1' AND r.name = 'READONLY'
  AND NOT EXISTS (SELECT 1 FROM account_role ar WHERE ar.account_id = a.id AND ar.role_id = r.id);
