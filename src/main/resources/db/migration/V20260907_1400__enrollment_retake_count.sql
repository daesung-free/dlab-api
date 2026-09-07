-- 재수 구분 (API_GAPS F-4.1-1)
--
-- ■ GradeType 에 값을 더하지 않는 이유
--
-- GradeType 은 시험 양식(exam_master·exam_subject)의 축이다. 여기에 재수·삼수를 더하면
-- 양식이 학년 수만큼 곱해져 늘고, 연도가 바뀔 때마다 그만큼 다시 넣어야 한다.
-- 그런데 재수든 삼수든 치는 시험은 같다 — 축이 다른 값이다.
--
-- 그리고 사수·오수는 값이 계속 늘어난다. enum 이면 그때마다 마이그레이션을 새로 쓰게 된다.
--
-- ■ 등록 이력으로 세지 않는 이유
--
-- 학생이 "사람 + 등록 건" 2단이라 우리 학원 재등록은 셀 수 있다. 그러나 다른 곳에서
-- 재수하고 우리 학원에 처음 오는 삼수생은 등록이 1건이라 값이 어긋난다.
-- 접수할 때 받는 값이다.
--
-- ■ NULL 의 의미
--
-- "N수가 아니거나 아직 안 받은 것"이다. 0 으로 채우면 현역과 구분되지 않는다.
ALTER TABLE student_enrollment
    ADD COLUMN retake_count SMALLINT;

ALTER TABLE student_enrollment
    ADD CONSTRAINT ck_enrollment_retake_count
        CHECK (retake_count IS NULL OR (retake_count >= 1 AND retake_count <= 10));

COMMENT ON COLUMN student_enrollment.retake_count IS
    'N수 차수 — 1=재수, 2=삼수, 3=사수. NULL 이면 해당 없음이거나 미입력';
