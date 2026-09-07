-- 성적 수정자 (API_GAPS 12-1)
--
-- 0826 회신이 "처음 입력시 학생, 이후 수정시에는 직원을 통해서"로 정했는데 직원 경로가
-- 없었다. 경로를 열면서 "누가 고쳤나"를 남긴다.
--
-- ■ created_by 로는 답이 안 된다
--
-- created_by 는 updatable=false 라 최초 작성자(=학생)만 남는다. 직원이 고쳐도 그대로다.
-- 그런데 이 값이 장학 취소 판정의 근거라, 학생 입력값을 직원이 고쳤다면 그 사실이 남아야 한다.
--
-- ■ 마지막 한 번만 남긴다
--
-- 전체 변경 이력은 감사 로그(F-C-1)가 할 일이다. 여기서는 화면이 "직원 확인됨"을
-- 표시할 수 있을 만큼만 둔다 — 이력 테이블을 성적에만 따로 만들면 나중에 감사 로그와 겹친다.
ALTER TABLE student_grade_submission
    ADD COLUMN modified_by BIGINT,
    ADD COLUMN modified_at TIMESTAMPTZ;

COMMENT ON COLUMN student_grade_submission.modified_by IS
    '마지막으로 고친 직원 account.id. NULL 이면 학생이 낸 그대로다';
