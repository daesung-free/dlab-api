-- 학생 영문명 · 졸업연도 (관리자 웹 학생 등록·조회)
--
-- 사람(student)에 붙는다 — 재등록해도 같은 값이다. 둘 다 선택 입력이다.
ALTER TABLE student
    ADD COLUMN english_name    VARCHAR(100),
    ADD COLUMN graduation_year SMALLINT
        CHECK (graduation_year IS NULL OR graduation_year BETWEEN 1990 AND 2100);
