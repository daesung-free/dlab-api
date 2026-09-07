-- V20260907120000: 특강에 코드 · 담당 강사
--
-- ⚠️ 파일명이 14자리인 이유 — lecture 테이블을 만드는 V20260806140000__lecture.sql 이
--    14자리라, 밑줄 방식(V20260907_1200)으로 지으면 20260907.1200 으로 파싱돼
--    20260806140000 보다 **먼저** 실행된다. 빈 DB 에서 "relation lecture does not exist"
--    로 깨지고, 이미 테이블이 있는 로컬에서는 통과하므로 CI 에서만 드러난다.
--    V20260903141000__lecture_teacher.sql 이 같은 이유로 14자리다.
--
-- 특강 기초 설정 화면(F-4.10-4)이 두 칸을 그리는데 서버에 자리가 없었다.
--
-- ★ instructor_name 이 왜 FK 가 아니라 문자열인가
--   teacher 테이블에는 **담당선생님(사감)만** 들어간다(CLAUDE.md §2). 특강 강사는
--   외부에서 부르는 경우가 있어 teacher 에 없을 수 있고, FK 로 박으면 그런 강사는
--   **아예 등록을 못 한다.**
--   지금 요구사항에서 강사로 하는 일은 목록에 표시하는 것 하나뿐이라(강사별 특강 조회·
--   이름 변경 자동 반영 같은 요건이 없다) 문자열로 충분하다. 강사별 조회가 요구되면
--   그때 마스터로 올리면 되고, 그 시점에 이 컬럼이 그대로 후보값이 된다.
--
-- ★ code 는 다른 기초 마스터와 같은 이유다 — 이름은 바뀌고 코드는 안 바뀐다.
--   지점·연도 안에서 유일하고, NULL 은 제약을 받지 않는다(선택 입력).

ALTER TABLE lecture ADD COLUMN code            VARCHAR(30);
ALTER TABLE lecture ADD COLUMN instructor_name VARCHAR(50);

CREATE UNIQUE INDEX uq_lecture_code
    ON lecture (academy_id, year, code) WHERE code IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN lecture.code IS '선택. 이름이 바뀌어도 유지되는 키';
COMMENT ON COLUMN lecture.instructor_name IS
    '담당 강사명. 외부 강사가 있어 teacher FK 로 묶지 않는다';
