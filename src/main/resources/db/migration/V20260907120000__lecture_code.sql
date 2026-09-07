-- V20260907120000: 특강 코드
--
-- ⚠️ 파일명이 14자리인 이유 — lecture 테이블을 만드는 V20260806140000__lecture.sql 이
--    14자리라, 밑줄 방식(V20260907_1200)으로 지으면 20260907.1200 으로 파싱돼
--    20260806140000 보다 **먼저** 실행된다. 빈 DB 에서 "relation lecture does not exist"
--    로 깨지고, 이미 테이블이 있는 로컬에서는 통과하므로 CI 에서만 드러난다.
--    V20260903141000__lecture_teacher.sql 이 같은 이유로 14자리다.
--
-- ★ 담당 강사는 여기서 안 만든다 — V20260903141000 의 teacher_id(FK)를 쓴다.
--   외부 강사를 담을 수 없다는 한계가 있으나(teacher 는 담당선생님만 들어간다),
--   실제로 그런 특강이 있는지 확인된 뒤에 확장하는 쪽으로 정했다.
--   여기서 문자열 컬럼을 하나 더 두면 화면의 '담당' 한 칸에 무엇을 찍을지가 갈린다.
--
-- code 는 다른 기초 마스터와 같은 이유다 — 이름은 바뀌고 코드는 안 바뀐다.
-- 지점·연도 안에서 유일하고, NULL 은 제약을 받지 않는다(선택 입력).

ALTER TABLE lecture ADD COLUMN code VARCHAR(30);

CREATE UNIQUE INDEX uq_lecture_code
    ON lecture (academy_id, year, code) WHERE code IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN lecture.code IS '선택. 이름이 바뀌어도 유지되는 키';
