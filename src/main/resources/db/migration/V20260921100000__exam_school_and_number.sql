-- 모의고사 학교코드 · 수험번호 (2026-09-21 연구소 답변서)
--
-- ★ 연구소 학교코드는 대성전산 acad_cd 와 다른 체계다.
--   김포·동탄이 서로 뒤집혀 있어(34→99702, 33→99703) 순서로 유추할 수 없다.
--   acad_cd 는 대성전산이 부여한 값이라 건드리지 않고 컬럼을 따로 둔다.
ALTER TABLE academy
    ADD COLUMN exam_school_cd VARCHAR(10);

COMMENT ON COLUMN academy.exam_school_cd IS
    '모의고사 자료의 학교코드(99700~99711). acad_cd 와 다른 체계다 — 순서로 유추하지 말 것';

UPDATE academy SET exam_school_cd = '99700' WHERE acad_cd = '31';  -- 분당
UPDATE academy SET exam_school_cd = '99701' WHERE acad_cd = '32';  -- 일산
UPDATE academy SET exam_school_cd = '99702' WHERE acad_cd = '34';  -- ★ 김포
UPDATE academy SET exam_school_cd = '99703' WHERE acad_cd = '33';  -- ★ 동탄
UPDATE academy SET exam_school_cd = '99704' WHERE acad_cd = '42';  -- 부천
UPDATE academy SET exam_school_cd = '99705' WHERE acad_cd = '43';  -- 이매
UPDATE academy SET exam_school_cd = '99706' WHERE acad_cd = '44';  -- 광명
UPDATE academy SET exam_school_cd = '99707' WHERE acad_cd = '45';  -- 목동
UPDATE academy SET exam_school_cd = '99708' WHERE acad_cd = '46';  -- 송파
UPDATE academy SET exam_school_cd = '99710' WHERE acad_cd = '47';  -- 대전
UPDATE academy SET exam_school_cd = '99711' WHERE acad_cd = '48';  -- 대구
-- 99709(외부생)는 지점이 아니다. 행으로 만들지 않는다 — 통계·권한·좌석이 오염된다

-- ★ 모의고사 반 번호를 반 마스터가 갖는다.
--   반 이름("고3 1반"·"N수 1반")에서 숫자를 뽑으면 서로 다른 반이 둘 다 1반이 되어
--   수험번호가 겹친다. 실제 파일은 지점 안에서 반 번호가 유일하다(1반 1003~ / 2반 2002~).
--   그래서 이름에서 추론하지 않고 값으로 받는다.
ALTER TABLE class_master
    ADD COLUMN exam_class_no SMALLINT;

COMMENT ON COLUMN class_master.exam_class_no IS
    '모의고사 반 번호. 비면 그 반 학생은 수험번호를 채번하지 않는다(이름 매칭으로 떨어진다)';

CREATE UNIQUE INDEX uq_class_master_exam_no
    ON class_master (academy_id, year, exam_class_no)
    WHERE is_deleted = FALSE AND exam_class_no IS NOT NULL;

-- ★ 수험번호는 등록 건에 붙는다. 한 번 부여되면 반이 바뀌어도 고치지 않는다 —
--   연구소가 "최초 부여받은 학번은 절대 변경 불가" 라고 명시했고, 과거 회차 성적이
--   그 번호로 들어와 있다.
ALTER TABLE student_enrollment
    ADD COLUMN exam_class_no    SMALLINT,
    ADD COLUMN exam_seq         SMALLINT,
    ADD COLUMN exam_student_no  VARCHAR(10),
    ADD COLUMN exam_no_fixed_at TIMESTAMPTZ;

COMMENT ON COLUMN student_enrollment.exam_student_no IS
    '모의고사 수험번호(반+3자리 순번, 예 1001). 우리 학번(2026-0001)과 다른 체계다';

-- 같은 지점·연도에 같은 수험번호가 둘이면 성적이 어느 학생 것인지 정해지지 않는다
CREATE UNIQUE INDEX uq_enrollment_exam_no
    ON student_enrollment (academy_id, year, exam_student_no)
    WHERE is_deleted = FALSE AND exam_student_no IS NOT NULL;
