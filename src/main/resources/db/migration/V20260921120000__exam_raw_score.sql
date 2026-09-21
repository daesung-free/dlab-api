-- 원점수
--
-- ★ 앱 성적 화면(시안 4.2)이 과목별로 등급·원점수·표준점수·백분위 네 가지를 보여준다.
--   특히 영어·한국사는 절대평가라 "원점수와 등급만" 보여주기로 정리됐는데, 원점수 칸이
--   없어서 그 두 과목은 값이 비어 나왔다. 연구소 파일(194열)에는 원점수가 들어 있고
--   파서도 이미 읽고 있었다 — 저장할 자리만 없었다.
ALTER TABLE student_exam_score
    ADD COLUMN raw_score SMALLINT;

-- 과목마다 원점수를 받는지. 다른 세 칸과 같은 방식이다.
-- ★ 기본값 FALSE — 기존 행은 전부 입학 전 성적 양식이고, 신상기록부 양식에는 원점수가
--   없다(표준점수·백분위·등급). 켜 두면 학생 가입 화면에 없던 입력 칸이 생긴다.
ALTER TABLE exam_subject
    ADD COLUMN has_raw_score BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN student_exam_score.raw_score IS '원점수. 연구소 파일에서 온다 — 영어·한국사는 원점수와 등급만 있다';
COMMENT ON COLUMN exam_subject.has_raw_score IS '원점수를 받는 과목인가. 입학 전 성적 양식은 FALSE(신상기록부에 없다)';
