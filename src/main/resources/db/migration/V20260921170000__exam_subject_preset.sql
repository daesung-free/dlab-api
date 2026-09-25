-- 학년별 과목 기본 구성 (0921 성적 문서 7장)
--
-- 디랩 시험(더프 월례고사 등)은 회차를 매달 새로 만든다. 그때마다 과목 6개와 칸 구성
-- (표준점수·백분위·등급·원점수)을 손으로 넣으면 한 칸만 틀려도 업로드에서 그 과목이 빠진다.
-- 학년별 기본 구성을 여기 두고, 회차를 만들 때 과목을 비우면 이걸로 채운다.
--
-- ★ 코드에 박지 않는다. 2027년 11월 수능부터 통합수능이라 학년별 과목이 또 바뀐다 —
--   행만 고치면 되게 둔다. 해가 바뀌면 롤오버 API 가 전년도 행을 복사한다.
--
-- academy_id 가 NULL 이면 전 지점 공통. 지점 행이 하나라도 있으면 그 지점은 그것만 쓴다
-- (exam_master 와 같은 규칙).
CREATE TABLE exam_subject_preset
(
    id                 BIGSERIAL PRIMARY KEY,
    academy_id         BIGINT      REFERENCES academy (id),
    year               SMALLINT    NOT NULL,
    grade_type         VARCHAR(10) NOT NULL,
    subject_code       VARCHAR(20) NOT NULL,
    subject_name       VARCHAR(30) NOT NULL,
    sort_order         SMALLINT    NOT NULL DEFAULT 0,
    has_standard_score BOOLEAN     NOT NULL DEFAULT TRUE,
    has_percentile     BOOLEAN     NOT NULL DEFAULT TRUE,
    has_grade_level    BOOLEAN     NOT NULL DEFAULT TRUE,
    has_raw_score      BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         BIGINT,
    is_deleted         BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_exam_subject_preset
    ON exam_subject_preset (COALESCE(academy_id, 0), year, grade_type, subject_code)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE exam_subject_preset IS
    '학년별 과목 기본 구성. 디랩 시험 회차를 만들 때 과목을 비우면 이걸로 채운다';

-- 2026 기준 (연구소 0921 — 고1·고2 는 통합형, 고3·N수는 탐구 선택)
-- 영어·한국사는 절대평가라 원점수와 등급만 온다
INSERT INTO exam_subject_preset (academy_id, year, grade_type, subject_code, subject_name,
                                 sort_order, has_standard_score, has_percentile,
                                 has_grade_level, has_raw_score, created_by)
SELECT NULL, 2026, g.grade, s.code, s.name, s.ord, s.std, s.std, TRUE, TRUE, 0
FROM (VALUES ('HIGH2')) AS g(grade)
CROSS JOIN (VALUES
        ('KOREAN',  '국어',     1, TRUE),
        ('MATH',    '수학',     2, TRUE),
        ('ENGLISH', '영어',     3, FALSE),
        ('HISTORY', '한국사',   4, FALSE),
        ('SOCIAL',  '통합사회', 5, TRUE),
        ('SCIENCE', '통합과학', 6, TRUE)
    ) AS s(code, name, ord, std);

INSERT INTO exam_subject_preset (academy_id, year, grade_type, subject_code, subject_name,
                                 sort_order, has_standard_score, has_percentile,
                                 has_grade_level, has_raw_score, created_by)
SELECT NULL, 2026, g.grade, s.code, s.name, s.ord, s.std, s.std, TRUE, TRUE, 0
FROM (VALUES ('HIGH3'), ('N_SU')) AS g(grade)
CROSS JOIN (VALUES
        ('KOREAN',   '국어',   1, TRUE),
        ('MATH',     '수학',   2, TRUE),
        ('ENGLISH',  '영어',   3, FALSE),
        ('HISTORY',  '한국사', 4, FALSE),
        ('INQUIRY1', '탐구1',  5, TRUE),
        ('INQUIRY2', '탐구2',  6, TRUE)
    ) AS s(code, name, ord, std);
