-- V20260820_1000: 학생 가입 성적 입력 — 모의고사 + 내신 (앱 A-2 / F-4.1)
--
-- 오래 막혀 있던 "내신 성적 입력 양식 미정"(§4 블로커)이 입학서류 신상기록부 3종으로
-- 풀렸다. 실물 양식을 보고 확인된 것:
--
--   ① 내신은 칸이 하나다 — "내신성적(주요교과평균)". 학년별 항목도, 학기 단위도,
--      등급/원점수 구분도 없다. 숫자 한 개다.
--   ② 모의고사는 학년에 따라 시험 구성과 과목이 통째로 다르다.
--        · 예비고2·예비고3 → 6월·9월·10월 학력평가 / 국어·수학·영어·통합사회·통합과학
--        · N수·현고3       → 6월·9월 평가원 + 전년도 수능 / 국어·수학·영어·탐구1·탐구2
--   ③ 각 성적은 표준점수·백분위·등급 3종이다. 단 한국사는 절대평가라 등급뿐이다.
--
-- ★ 그래서 과목·시험을 코드나 enum에 박지 않고 마스터 데이터로 둔다.
--   근거가 셋이다. (1) 위처럼 학년마다 다르고, (2) 2028 수능 개편으로 통합사회·통합과학이
--   또 바뀌며, (3) 탐구 과목은 학생 선택에 따라 갈린다. enum으로 두면 해마다
--   마이그레이션을 새로 쓰게 되고, 그때 과거 학생 성적이 어느 과목이었는지가 흐려진다.
--   learning_plan_option에서 같은 판단을 했다.

-- ─────────────────────────────────────────────────────────────
-- 1. 시험 회차 마스터
--
-- ★ academy_id가 NULL이면 전 지점 공통이다(holiday·terms와 같은 규약).
--   성적 양식이 지점마다 다를 이유는 지금 없지만, 지점이 한 학년만 다르게 받고 싶을 때
--   전 지점 행을 건드리면 나머지 8개 지점이 같이 바뀐다.
--   조회는 "그 지점 행이 하나라도 있으면 그것만, 없으면 공통본"으로 가른다 —
--   둘을 합치면 같은 시험이 두 번 나온다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE exam_master (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      REFERENCES academy (id),
    year        SMALLINT    NOT NULL,

    grade_type  VARCHAR(10) NOT NULL CHECK (grade_type IN ('HIGH2','HIGH3','N_SU')),

    -- JUNE/SEPT/OCT = 6·9·10월, CSAT = 수능.
    -- "6월 학력평가"인지 "6월 평가원"인지는 grade_type이 이미 가르므로 코드를 나누지 않는다
    exam_code   VARCHAR(20) NOT NULL CHECK (exam_code IN ('JUNE','SEPT','OCT','CSAT')),

    -- 화면 표시용 원문. "2026년 6월 학력평가" / "2026학년도 수능 성적"처럼
    -- 신상기록부에 적힌 그대로 넣는다. 서버가 연도를 조합해 만들지 않는다 —
    -- 수능은 응시 연도와 학년도가 어긋나(2025년 11월 = 2026학년도) 조합식이 매번 틀린다
    exam_name   VARCHAR(64) NOT NULL,

    sort_order  SMALLINT    NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ PostgreSQL은 NULL을 서로 다른 값으로 보므로 academy_id를 포함한 유니크 하나로는
--   공통 행의 중복을 못 막는다. 부분 인덱스 두 벌로 나눈다(terms와 같은 처리)
CREATE UNIQUE INDEX uq_exam_master_common
    ON exam_master (year, grade_type, exam_code)
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_exam_master_academy
    ON exam_master (academy_id, year, grade_type, exam_code)
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

CREATE INDEX idx_exam_master_lookup
    ON exam_master (year, grade_type, sort_order) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 2. 시험별 과목
--
-- ★ 시험마다 따로 둔다. 같은 학년이라도 10월 학평·수능에만 한국사가 붙어서,
--   학년 단위로 한 벌만 두면 6월 시험에도 한국사 칸이 뜬다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE exam_subject (
    id             BIGSERIAL   PRIMARY KEY,
    exam_master_id BIGINT      NOT NULL REFERENCES exam_master (id),

    -- KOREAN/MATH/ENGLISH/SOCIAL/SCIENCE/INQUIRY1/INQUIRY2/HISTORY 등.
    -- 통계에서 학년이 달라도 국어끼리는 묶이게 하는 축이다
    subject_code   VARCHAR(20) NOT NULL,
    subject_name   VARCHAR(30) NOT NULL,
    sort_order     SMALLINT    NOT NULL DEFAULT 0,

    -- ★ 한국사는 절대평가라 등급만 있다. 세 칸을 일괄로 열면 학생이 없는 점수를
    --   지어내 채우고, 그 값이 통계에 그대로 들어간다
    has_standard_score BOOLEAN NOT NULL DEFAULT TRUE,
    has_percentile     BOOLEAN NOT NULL DEFAULT TRUE,
    has_grade_level    BOOLEAN NOT NULL DEFAULT TRUE,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_exam_subject
    ON exam_subject (exam_master_id, subject_code) WHERE is_deleted = FALSE;

CREATE INDEX idx_exam_subject_list
    ON exam_subject (exam_master_id, sort_order) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 3. 학생 성적 제출 (등록 건당 1벌)
--
-- ★ 내신은 여기 컬럼으로 둔다. 값이 하나뿐이라 별도 테이블을 만들 이유가 없다.
--
-- ★ "성적을 모른다"를 반드시 표현할 수 있어야 한다. 가입 시점에 성적표가 없는 학생이
--   실제로 있는데(자퇴·검정고시·성적표 분실), 필수로 막으면 가입 자체를 못 한다.
--   그렇다고 0을 넣게 두면 통계에서 진짜 0점과 구분되지 않는다 — 사유를 남기고 건너뛴다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student_grade_submission (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 내신 주요교과평균. 등급(1.00~9.00)으로 적는 학생과 원점수로 적는 학생이 섞이므로
    -- 범위를 좁게 잡지 않는다. 해석은 상담 교사가 한다
    main_subject_average NUMERIC(5,2) CHECK (main_subject_average >= 0),

    -- 모의고사 전체를 건너뛴 경우
    exam_skipped  BOOLEAN     NOT NULL DEFAULT FALSE,
    skip_reason   VARCHAR(200),

    submitted_at  TIMESTAMPTZ,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 등록 건당 한 벌. 여러 벌이면 어느 것이 이 학생의 성적인지 상담 화면이 고를 수 없다
CREATE UNIQUE INDEX uq_student_grade_submission
    ON student_grade_submission (enrollment_id) WHERE is_deleted = FALSE;

-- 건너뛴 경우에만 사유가 있고, 건너뛰지 않았는데 사유가 있으면 앞뒤가 안 맞는다
ALTER TABLE student_grade_submission
    ADD CONSTRAINT ck_grade_submission_skip
    CHECK (exam_skipped OR skip_reason IS NULL);

-- ─────────────────────────────────────────────────────────────
-- 4. 과목별 점수
--
-- ★ 세 칸이 전부 nullable이다. 미응시 과목·절대평가 과목·기억나지 않는 칸이 실제로 있고,
--   0으로 채우면 진짜 0점과 구분되지 않는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student_exam_score (
    id              BIGSERIAL   PRIMARY KEY,
    submission_id   BIGINT      NOT NULL REFERENCES student_grade_submission (id),
    exam_master_id  BIGINT      NOT NULL REFERENCES exam_master (id),
    exam_subject_id BIGINT      NOT NULL REFERENCES exam_subject (id),

    -- 표준점수는 국어·수학이 150을 넘기도 한다. 상한은 오타 방어용으로만 둔다
    standard_score  SMALLINT    CHECK (standard_score BETWEEN 0 AND 200),
    percentile      SMALLINT    CHECK (percentile BETWEEN 0 AND 100),
    grade_level     SMALLINT    CHECK (grade_level BETWEEN 1 AND 9),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_student_exam_score
    ON student_exam_score (submission_id, exam_subject_id) WHERE is_deleted = FALSE;

CREATE INDEX idx_student_exam_score_submission
    ON student_exam_score (submission_id, exam_master_id) WHERE is_deleted = FALSE;

COMMENT ON COLUMN student_grade_submission.main_subject_average IS
    '내신 주요교과평균. 신상기록부의 "내신성적(주요교과평균)" 한 칸 그대로';
COMMENT ON COLUMN student_grade_submission.exam_skipped IS
    '모의고사 성적을 모른다고 체크. 0으로 채우면 진짜 0점과 구분되지 않는다';
COMMENT ON COLUMN exam_subject.has_standard_score IS
    '한국사처럼 절대평가 과목은 FALSE. 없는 칸을 열면 학생이 지어내 채운다';

-- ─────────────────────────────────────────────────────────────
-- 5. 2026년 양식 초기 데이터
--
-- 입학서류 신상기록부 3종을 그대로 옮긴 것이다. 학년 구분은 "가입 시점의 현재 학년"이다
-- (CLAUDE.md 고2/고3/n수생 3분류):
--   HIGH2  = 현 고2(=예비고3) → 6·9·10월 학력평가 · 통합사회/통합과학 (2028 수능 개편 과정)
--   HIGH3  = 현 고3           → 6·9월 평가원 + 전년도 수능 · 탐구1/탐구2
--   N_SU   = n수생            → HIGH3와 동일
--
-- ⚠️ 연도가 바뀌면 행을 새로 넣어야 한다. 마이그레이션이 아니라 관리자 화면
--    (/api/v1/admin/exam-forms)에서 넣는다 — 그래서 여기 2026만 있다.
--    안 넣으면 그 해 가입자가 EXAM_FORM_NOT_FOUND를 받는다.
--
-- ★ 한국사는 등급만이다(절대평가). 세 칸을 일괄로 열면 학생이 없는 점수를 지어내 채운다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO exam_master (academy_id, year, grade_type, exam_code, exam_name, sort_order, created_by)
VALUES
    (NULL, 2026, 'HIGH2', 'JUNE', '2026년 6월 학력평가',  1, 0),
    (NULL, 2026, 'HIGH2', 'SEPT', '2026년 9월 학력평가',  2, 0),
    (NULL, 2026, 'HIGH2', 'OCT',  '2026년 10월 학력평가', 3, 0),
    (NULL, 2026, 'HIGH3', 'JUNE', '6월 평가원 모의고사',   1, 0),
    (NULL, 2026, 'HIGH3', 'SEPT', '9월 평가원 모의고사',   2, 0),
    (NULL, 2026, 'HIGH3', 'CSAT', '2026학년도 수능',      3, 0),
    (NULL, 2026, 'N_SU',  'JUNE', '6월 평가원 모의고사',   1, 0),
    (NULL, 2026, 'N_SU',  'SEPT', '9월 평가원 모의고사',   2, 0),
    (NULL, 2026, 'N_SU',  'CSAT', '2026학년도 수능',      3, 0);

-- 재학생(현 고2) — 통합사회·통합과학
INSERT INTO exam_subject (exam_master_id, subject_code, subject_name, sort_order,
                          has_standard_score, has_percentile, has_grade_level, created_by)
SELECT m.id, s.code, s.name, s.ord, s.std, s.pct, TRUE, 0
FROM exam_master m
CROSS JOIN (VALUES
        ('KOREAN',  '국어',     1, TRUE, TRUE),
        ('MATH',    '수학',     2, TRUE, TRUE),
        ('ENGLISH', '영어',     3, TRUE, TRUE),
        ('SOCIAL',  '통합사회', 4, TRUE, TRUE),
        ('SCIENCE', '통합과학', 5, TRUE, TRUE)
    ) AS s(code, name, ord, std, pct)
WHERE m.year = 2026 AND m.grade_type = 'HIGH2';

-- N수·현고3 — 탐구1/탐구2
INSERT INTO exam_subject (exam_master_id, subject_code, subject_name, sort_order,
                          has_standard_score, has_percentile, has_grade_level, created_by)
SELECT m.id, s.code, s.name, s.ord, s.std, s.pct, TRUE, 0
FROM exam_master m
CROSS JOIN (VALUES
        ('KOREAN',    '국어',   1, TRUE, TRUE),
        ('MATH',      '수학',   2, TRUE, TRUE),
        ('ENGLISH',   '영어',   3, TRUE, TRUE),
        ('INQUIRY1',  '탐구1',  4, TRUE, TRUE),
        ('INQUIRY2',  '탐구2',  5, TRUE, TRUE)
    ) AS s(code, name, ord, std, pct)
WHERE m.year = 2026 AND m.grade_type IN ('HIGH3', 'N_SU');

-- 한국사는 10월 학평·수능에만 붙고 등급만 받는다
INSERT INTO exam_subject (exam_master_id, subject_code, subject_name, sort_order,
                          has_standard_score, has_percentile, has_grade_level, created_by)
SELECT m.id, 'HISTORY', '한국사', 9, FALSE, FALSE, TRUE, 0
FROM exam_master m
WHERE m.year = 2026 AND m.exam_code IN ('OCT', 'CSAT');
