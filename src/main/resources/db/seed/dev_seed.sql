-- 로컬 개발용 시드 — 화면 연동 확인용. **운영 금지.**
--
-- ★ 파일명에 V 접두사를 붙이지 않는다. 이 디렉터리는 Flyway 가 읽지 않는 손 세팅용인데
--   (CLAUDE.md §2), 누군가 locations 에 db/seed 를 추가하더라도 이 파일만은 집히지 않게 한다.
--   시드가 flyway_schema_history 에 들어가면 두 가지가 터진다:
--     · 시드를 고칠 때마다 체크섬 불일치로 기동이 실패한다 (시드는 자주 바뀐다)
--     · §4 대로 개발 서버가 그대로 운영이 되면, 그 DB 를 prod 프로필로 띄울 때
--       "applied but not resolved" 로 validate 가 실패한다
--
-- 실행: ./scripts/seed-local.sh   (지점·교시 시드까지 순서대로 넣는다)
--
-- 멱등이다 — 여러 번 돌려도 중복으로 쌓이지 않는다.
BEGIN;

-- 관리자 계정: admin / dlab1234!  (SUPER_ADMIN → 전 지점 조회)
INSERT INTO employee (academy_id, name, dept_name, position_name, phone, email, hired_date)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), '통합관리자', '운영팀', '실장', '010-0000-0000', 'admin@dlab.local', DATE '2026-01-02'
WHERE NOT EXISTS (SELECT 1 FROM employee WHERE email = 'admin@dlab.local');

INSERT INTO account (account_type, login_id, password_hash, status, employee_id, must_change_password, password_changed_at)
SELECT 'EMPLOYEE', 'admin', '$2b$10$weDEDd3JAASEyc5IKiZnfeErJcqgk3hdajGSDEb.PvihwrFuOAECK', 'ACTIVE', e.id, FALSE, now()
FROM employee e WHERE e.email = 'admin@dlab.local'
  AND NOT EXISTS (SELECT 1 FROM account WHERE login_id = 'admin');

INSERT INTO account_role (account_id, role_id)
SELECT a.id, r.id FROM account a, role r
WHERE a.login_id = 'admin' AND r.name = 'SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM account_role ar WHERE ar.account_id = a.id AND ar.role_id = r.id);

-- 지점 관리자 계정: branch / dlab1234!  (BRANCH_ADMIN · 분당)
--
-- ★ 이 계정이 따로 필요한 이유: 출결·상벌점·상담 등 8개 엔드포인트는 전 지점 권한(SUPER_ADMIN)으로
--   호출하면 "지점을 지정해야 합니다"로 막힌다(docs/API_GAPS.md 1-1).
--   그 화면들을 확인하려면 지점이 정해진 계정이어야 한다.
INSERT INTO employee (academy_id, name, dept_name, position_name, email, hired_date)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), '분당관리자', '운영팀', '팀장', 'branch@dlab.local', DATE '2026-01-02'
WHERE NOT EXISTS (SELECT 1 FROM employee WHERE email = 'branch@dlab.local');

INSERT INTO account (account_type, login_id, password_hash, status, employee_id, must_change_password, password_changed_at)
SELECT 'EMPLOYEE', 'branch', '$2b$10$weDEDd3JAASEyc5IKiZnfeErJcqgk3hdajGSDEb.PvihwrFuOAECK', 'ACTIVE', e.id, FALSE, now()
FROM employee e WHERE e.email = 'branch@dlab.local'
  AND NOT EXISTS (SELECT 1 FROM account WHERE login_id = 'branch');

INSERT INTO account_role (account_id, role_id)
SELECT a.id, r.id FROM account a, role r
WHERE a.login_id = 'branch' AND r.name = 'BRANCH_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM account_role ar WHERE ar.account_id = a.id AND ar.role_id = r.id);

-- 샘플 학생 60명 (2026년 코호트, 3개 지점)
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260001', '서도윤', '010-3832-7521', DATE '2007-05-04', 'F', '낙생고', '서도윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260001');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0001', 'R00001', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-02-11'
FROM student s WHERE s.unique_code = 'S20260001'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0001');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260002', '임민주', '010-8760-1020', DATE '2008-02-18', 'F', '송림고', '임민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260002');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0001', 'R00002', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-02-26'
FROM student s WHERE s.unique_code = 'S20260002'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0001');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260003', '임승민', '010-9291-7332', DATE '2007-06-21', 'F', '송림고', '임승민', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260003');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0001', 'R00003', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-02-26'
FROM student s WHERE s.unique_code = 'S20260003'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0001');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260004', '한민주', '010-3293-2738', DATE '2007-08-26', 'F', '보평고', '한민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260004');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0002', 'R00004', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-01-01'
FROM student s WHERE s.unique_code = 'S20260004'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0002');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260005', '박수빈', '010-5279-3252', DATE '2007-04-18', 'F', '보평고', '박수빈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260005');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0002', 'R00005', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-03-07'
FROM student s WHERE s.unique_code = 'S20260005'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0002');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260006', '장도윤', '010-5112-2336', DATE '2006-05-10', 'F', '이매고', '장도윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260006');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0002', 'R00006', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-03-23'
FROM student s WHERE s.unique_code = 'S20260006'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0002');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260007', '신하윤', '010-4876-3587', DATE '2007-07-09', 'F', '보평고', '신하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260007');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0003', 'R00007', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-01-21'
FROM student s WHERE s.unique_code = 'S20260007'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0003');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260008', '조태윤', '010-4876-1925', DATE '2006-10-03', 'F', '한솔고', '조태윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260008');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0003', 'R00008', TRUE, 'HIGH2', 'HUMANITIES', 'LEAVE', DATE '2026-03-27'
FROM student s WHERE s.unique_code = 'S20260008'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0003');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260009', '윤민재', '010-8744-8323', DATE '2007-11-09', 'M', '송림고', '윤민재', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260009');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0003', 'R00009', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-02-08'
FROM student s WHERE s.unique_code = 'S20260009'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0003');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260010', '윤도윤', '010-7334-3153', DATE '2008-01-20', 'M', '태원고', '윤도윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260010');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0004', 'R00010', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-02-03'
FROM student s WHERE s.unique_code = 'S20260010'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0004');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260011', '김채원', '010-5116-4321', DATE '2007-07-06', 'F', '보평고', '김채원', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260011');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0004', 'R00011', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-02-23'
FROM student s WHERE s.unique_code = 'S20260011'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0004');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260012', '이채원', '010-4411-7329', DATE '2008-07-10', 'F', '한솔고', '이채원', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260012');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0004', 'R00012', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-10'
FROM student s WHERE s.unique_code = 'S20260012'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0004');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260013', '권하윤', '010-8687-6442', DATE '2008-11-27', 'M', '낙생고', '권하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260013');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0005', 'R00013', TRUE, 'N_SU', 'SCIENCE', 'ENROLLED', DATE '2026-02-08'
FROM student s WHERE s.unique_code = 'S20260013'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0005');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260014', '김서준', '010-8989-3841', DATE '2006-08-28', 'M', '낙생고', '김서준', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260014');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0005', 'R00014', TRUE, 'N_SU', 'SCIENCE', 'ENROLLED', DATE '2026-01-22'
FROM student s WHERE s.unique_code = 'S20260014'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0005');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260015', '박민주', '010-6898-7293', DATE '2006-10-22', 'F', '보평고', '박민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260015');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0005', 'R00015', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-09'
FROM student s WHERE s.unique_code = 'S20260015'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0005');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260016', '권하늘', '010-7670-7092', DATE '2007-08-10', 'F', '보평고', '권하늘', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260016');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0006', 'R00016', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-14'
FROM student s WHERE s.unique_code = 'S20260016'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0006');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260017', '최서준', '010-4821-3855', DATE '2006-07-01', 'M', '이매고', '최서준', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260017');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0006', 'R00017', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-03-07'
FROM student s WHERE s.unique_code = 'S20260017'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0006');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260018', '정유나', '010-6646-2530', DATE '2008-12-23', 'M', '이매고', '정유나', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260018');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0006', 'R00018', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-03-25'
FROM student s WHERE s.unique_code = 'S20260018'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0006');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260019', '권도현', '010-9152-8545', DATE '2007-12-09', 'F', '송림고', '권도현', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260019');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0007', 'R00019', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-03-11'
FROM student s WHERE s.unique_code = 'S20260019'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0007');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260020', '조세훈', '010-7616-4156', DATE '2008-12-03', 'F', '태원고', '조세훈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260020');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0007', 'R00020', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-09'
FROM student s WHERE s.unique_code = 'S20260020'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0007');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260021', '최민재', '010-6652-1206', DATE '2008-10-13', 'M', '송림고', '최민재', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260021');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0007', 'R00021', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-27'
FROM student s WHERE s.unique_code = 'S20260021'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0007');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260022', '권지호', '010-9897-9895', DATE '2007-02-19', 'M', '유신고', '권지호', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260022');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0008', 'R00022', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-03-04'
FROM student s WHERE s.unique_code = 'S20260022'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0008');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260023', '조지우', '010-1286-6705', DATE '2008-08-03', 'F', '태원고', '조지우', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260023');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0008', 'R00023', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-01-19'
FROM student s WHERE s.unique_code = 'S20260023'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0008');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260024', '임하윤', '010-1667-8216', DATE '2007-06-21', 'F', '송림고', '임하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260024');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0008', 'R00024', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-03-16'
FROM student s WHERE s.unique_code = 'S20260024'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0008');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260025', '윤지우', '010-9396-8344', DATE '2006-08-22', 'M', '분당고', '윤지우', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260025');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0009', 'R00025', TRUE, 'N_SU', 'SCIENCE', 'ENROLLED', DATE '2026-02-07'
FROM student s WHERE s.unique_code = 'S20260025'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0009');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260026', '장채원', '010-8343-1582', DATE '2007-07-13', 'F', '한솔고', '장채원', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260026');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0009', 'R00026', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-01-14'
FROM student s WHERE s.unique_code = 'S20260026'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0009');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260027', '정서연', '010-8587-7345', DATE '2008-02-23', 'F', '분당고', '정서연', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260027');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0009', 'R00027', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-01-04'
FROM student s WHERE s.unique_code = 'S20260027'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0009');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260028', '신도윤', '010-2323-4383', DATE '2006-08-19', 'F', '한솔고', '신도윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260028');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0010', 'R00028', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-14'
FROM student s WHERE s.unique_code = 'S20260028'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0010');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260029', '정지우', '010-5957-6038', DATE '2006-04-22', 'F', '보평고', '정지우', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260029');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0010', 'R00029', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-03-28'
FROM student s WHERE s.unique_code = 'S20260029'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0010');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260030', '신세훈', '010-5740-1717', DATE '2008-09-24', 'F', '이매고', '신세훈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260030');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0010', 'R00030', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-01-05'
FROM student s WHERE s.unique_code = 'S20260030'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0010');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260031', '강지호', '010-7650-6805', DATE '2007-08-13', 'F', '낙생고', '강지호', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260031');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0011', 'R00031', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-02-12'
FROM student s WHERE s.unique_code = 'S20260031'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0011');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260032', '오유나', '010-3006-6152', DATE '2006-01-07', 'M', '분당고', '오유나', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260032');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0011', 'R00032', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-01-09'
FROM student s WHERE s.unique_code = 'S20260032'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0011');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260033', '강민주', '010-3876-7584', DATE '2008-06-06', 'F', '분당고', '강민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260033');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0011', 'R00033', TRUE, 'HIGH3', 'SCIENCE', 'LEAVE', DATE '2026-02-15'
FROM student s WHERE s.unique_code = 'S20260033'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0011');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260034', '조승민', '010-9837-3509', DATE '2008-12-08', 'M', '유신고', '조승민', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260034');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0012', 'R00034', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-01-15'
FROM student s WHERE s.unique_code = 'S20260034'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0012');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260035', '장민주', '010-1206-5438', DATE '2008-07-09', 'F', '분당고', '장민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260035');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0012', 'R00035', TRUE, 'N_SU', 'SCIENCE', 'LEAVE', DATE '2026-03-15'
FROM student s WHERE s.unique_code = 'S20260035'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0012');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260036', '최현준', '010-4415-5829', DATE '2006-11-24', 'F', '태원고', '최현준', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260036');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0012', 'R00036', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-03-02'
FROM student s WHERE s.unique_code = 'S20260036'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0012');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260037', '정태윤', '010-3653-7123', DATE '2008-03-26', 'M', '보평고', '정태윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260037');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0013', 'R00037', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-03-14'
FROM student s WHERE s.unique_code = 'S20260037'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0013');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260038', '임민재', '010-8813-9685', DATE '2008-11-24', 'F', '낙생고', '임민재', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260038');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0013', 'R00038', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-03-01'
FROM student s WHERE s.unique_code = 'S20260038'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0013');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260039', '서승민', '010-9774-8603', DATE '2006-02-03', 'F', '분당고', '서승민', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260039');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0013', 'R00039', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-03-15'
FROM student s WHERE s.unique_code = 'S20260039'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0013');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260040', '윤하윤', '010-4889-9394', DATE '2006-05-02', 'F', '이매고', '윤하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260040');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0014', 'R00040', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-01-20'
FROM student s WHERE s.unique_code = 'S20260040'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0014');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260041', '이채원', '010-5633-7416', DATE '2006-04-05', 'F', '송림고', '이채원', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260041');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0014', 'R00041', TRUE, 'HIGH3', 'HUMANITIES', 'WITHDRAWN', DATE '2026-03-20'
FROM student s WHERE s.unique_code = 'S20260041'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0014');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260042', '박현준', '010-9579-3540', DATE '2007-10-01', 'M', '분당고', '박현준', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260042');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0014', 'R00042', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-02-08'
FROM student s WHERE s.unique_code = 'S20260042'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0014');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260043', '강민주', '010-8692-7523', DATE '2007-11-03', 'F', '보평고', '강민주', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260043');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0015', 'R00043', TRUE, 'N_SU', 'SCIENCE', 'ENROLLED', DATE '2026-03-12'
FROM student s WHERE s.unique_code = 'S20260043'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0015');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260044', '강지우', '010-2448-4404', DATE '2007-07-05', 'M', '분당고', '강지우', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260044');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0015', 'R00044', TRUE, 'N_SU', 'HUMANITIES', 'ENROLLED', DATE '2026-02-28'
FROM student s WHERE s.unique_code = 'S20260044'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0015');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260045', '김도현', '010-8740-9410', DATE '2006-06-12', 'F', '이매고', '김도현', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260045');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0015', 'R00045', TRUE, 'N_SU', 'SCIENCE', 'WITHDRAWN', DATE '2026-02-25'
FROM student s WHERE s.unique_code = 'S20260045'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0015');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260046', '강서연', '010-5146-5017', DATE '2008-03-09', 'M', '태원고', '강서연', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260046');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0016', 'R00046', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-02-14'
FROM student s WHERE s.unique_code = 'S20260046'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0016');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260047', '조민재', '010-8790-6098', DATE '2007-11-01', 'F', '유신고', '조민재', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260047');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0016', 'R00047', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-01-03'
FROM student s WHERE s.unique_code = 'S20260047'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0016');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260048', '강서연', '010-3857-1519', DATE '2008-12-03', 'F', '한솔고', '강서연', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260048');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0016', 'R00048', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-03-27'
FROM student s WHERE s.unique_code = 'S20260048'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0016');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260049', '서유나', '010-4166-2718', DATE '2006-03-14', 'F', '분당고', '서유나', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260049');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0017', 'R00049', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-02-18'
FROM student s WHERE s.unique_code = 'S20260049'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0017');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260050', '박하윤', '010-2402-1053', DATE '2008-03-19', 'F', '낙생고', '박하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260050');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0017', 'R00050', TRUE, 'HIGH3', 'HUMANITIES', 'WITHDRAWN', DATE '2026-01-05'
FROM student s WHERE s.unique_code = 'S20260050'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0017');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260051', '한수빈', '010-5995-8049', DATE '2008-05-02', 'M', '분당고', '한수빈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260051');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0017', 'R00051', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-03-11'
FROM student s WHERE s.unique_code = 'S20260051'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0017');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260052', '서현준', '010-3179-9985', DATE '2006-08-15', 'F', '송림고', '서현준', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260052');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0018', 'R00052', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-03-17'
FROM student s WHERE s.unique_code = 'S20260052'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0018');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260053', '신세훈', '010-1372-5920', DATE '2006-09-24', 'M', '송림고', '신세훈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260053');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0018', 'R00053', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-03-11'
FROM student s WHERE s.unique_code = 'S20260053'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0018');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260054', '이도현', '010-1448-2904', DATE '2006-01-01', 'M', '보평고', '이도현', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260054');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0018', 'R00054', TRUE, 'N_SU', 'SCIENCE', 'LEAVE', DATE '2026-01-22'
FROM student s WHERE s.unique_code = 'S20260054'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0018');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260055', '오태윤', '010-8446-5134', DATE '2006-06-14', 'F', '낙생고', '오태윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260055');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0019', 'R00055', TRUE, 'HIGH2', 'HUMANITIES', 'WITHDRAWN', DATE '2026-02-26'
FROM student s WHERE s.unique_code = 'S20260055'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0019');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260056', '한하윤', '010-5299-3851', DATE '2006-03-22', 'F', '분당고', '한하윤', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260056');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0019', 'R00056', TRUE, 'HIGH3', 'SCIENCE', 'ENROLLED', DATE '2026-02-05'
FROM student s WHERE s.unique_code = 'S20260056'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0019');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260057', '임하늘', '010-3851-8311', DATE '2008-01-04', 'M', '송림고', '임하늘', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260057');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0019', 'R00057', TRUE, 'HIGH2', 'SCIENCE', 'ENROLLED', DATE '2026-02-18'
FROM student s WHERE s.unique_code = 'S20260057'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0019');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260058', '오도현', '010-9405-1608', DATE '2008-10-17', 'M', '한솔고', '오도현', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260058');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '31'), 2026, '2026-0020', 'R00058', TRUE, 'HIGH2', 'HUMANITIES', 'ENROLLED', DATE '2026-01-18'
FROM student s WHERE s.unique_code = 'S20260058'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.student_no = '2026-0020');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260059', '강세훈', '010-7529-2162', DATE '2008-01-06', 'M', '한솔고', '강세훈', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260059');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '32'), 2026, '2026-0020', 'R00059', TRUE, 'HIGH3', 'HUMANITIES', 'ENROLLED', DATE '2026-02-13'
FROM student s WHERE s.unique_code = 'S20260059'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '32') AND e.year = 2026 AND e.student_no = '2026-0020');
INSERT INTO student (unique_code, name, phone, birth_date, gender, school_name, search_name_normalized, onboarding_status)
SELECT 'S20260060', '권하늘', '010-4437-9921', DATE '2006-11-14', 'M', '낙생고', '권하늘', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM student WHERE unique_code = 'S20260060');
INSERT INTO student_enrollment (student_id, academy_id, year, student_no, rfid_no, is_current, grade, track, enrollment_status, admission_date)
SELECT s.id, (SELECT id FROM academy WHERE acad_cd = '33'), 2026, '2026-0020', 'R00060', TRUE, 'N_SU', 'SCIENCE', 'ENROLLED', DATE '2026-03-12'
FROM student s WHERE s.unique_code = 'S20260060'
  AND NOT EXISTS (SELECT 1 FROM student_enrollment e WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '33') AND e.year = 2026 AND e.student_no = '2026-0020');
COMMIT;
