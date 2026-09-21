-- 담임 예외 지정 (0921 연구소 답변 · 성적 문서 9장)
--
-- ★ 연구소가 뒤집었다 — 이전엔 "필요 없다" 였는데 "반은 그대로이나 담임 선생님 변경을
--   요청하는 경우가 있다, 수동 변경 기능이 필요하다" 로 바뀌었다.
--
-- ★ 반 담임이 기본값이고 이건 예외다. 해석은 한 곳(HomeroomResolver)에서만 한다.
--   학생 기준인 곳(승인 이양·상담 담당·학생 목록 담임 표시)만 이 값을 보고,
--   반 기준인 곳(반공지·반설문 작성 권한·전년도 복사)은 반 담임 그대로다 —
--   반 권한까지 열면 예외 학생 하나 때문에 그 반 전체를 건드릴 수 있게 된다.
ALTER TABLE student_enrollment
    ADD COLUMN homeroom_override_teacher_id BIGINT REFERENCES teacher (id),
    -- 권한이 따라 움직이는 값이라 "누가 왜 바꿨나" 가 없으면 나중에 되짚지 못한다
    ADD COLUMN homeroom_override_reason     VARCHAR(200),
    ADD COLUMN homeroom_override_at         TIMESTAMPTZ;

COMMENT ON COLUMN student_enrollment.homeroom_override_teacher_id IS
    '담임 예외 지정. 비면 반 담임. 반이 바뀌면 자동 해제된다';
