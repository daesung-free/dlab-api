-- 특강 담당 강사 (API_GAPS 7-5)
--
-- 목업의 '담당' 컬럼과 개설 폼의 강사 선택에 대응하는 값이 없었다.
--
-- ⚠️ teacher 는 담당선생님(사감)만 들어가는 테이블이다(행정은 employee).
--    외부 강사를 세우는 특강이 있으면 이 FK 로는 담을 수 없다 — 확인 후 확장한다.
--    지금은 화면의 강사 선택이 /staff/teachers 목록을 쓰는 것을 전제로 한다.
ALTER TABLE lecture
    ADD COLUMN teacher_id BIGINT REFERENCES teacher (id);

CREATE INDEX idx_lecture_teacher ON lecture (teacher_id) WHERE teacher_id IS NOT NULL;

COMMENT ON COLUMN lecture.teacher_id IS '담당 강사. 미정이면 비어 있다';
