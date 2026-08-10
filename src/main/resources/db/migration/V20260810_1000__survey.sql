-- V20260810_1000: 설문 (F-4.11-3 · A-14)
--
-- 두 용도가 한 구조를 쓴다 — 일반 설문(GENERAL)과 가채점 설문(GRADE_INPUT)이다.
-- 가채점은 "과목별 자기 점수"라 숫자 문항 여러 개일 뿐이고, 별도 테이블을 만들면
-- 배포·마감·집계 로직을 두 번 짜게 된다.
--
-- ★ 문항을 JSON 한 덩어리로 넣지 않았다. 결과 집계(선택지별 응답 수)가 관리자 화면의
--   본체인데, JSON에 넣으면 그 집계를 애플리케이션에서 파싱해서 세게 된다.
--   문항·선택지를 행으로 두면 GROUP BY 한 번이다.

CREATE TABLE survey (
    id               BIGSERIAL   PRIMARY KEY,

    -- NULL이면 전 지점(scope=ALL)이다. notice·holiday와 같은 방식.
    academy_id       BIGINT      REFERENCES academy (id),
    year             SMALLINT    NOT NULL,

    survey_type      VARCHAR(20) NOT NULL
        CHECK (survey_type IN ('GENERAL', 'GRADE_INPUT')),

    scope            VARCHAR(20) NOT NULL
        CHECK (scope IN ('ALL', 'BRANCH', 'CLASS')),
    -- scope=CLASS일 때만 채운다
    class_master_id  BIGINT      REFERENCES class_master (id),

    title            VARCHAR(200) NOT NULL,
    description      TEXT,

    -- ★ 익명 설문은 응답에 응답자를 남기지 않는다(아래 survey_response 참고).
    anonymous        BOOLEAN     NOT NULL DEFAULT FALSE,

    -- ★ 기간은 서버가 판정한다. 앱이 시각을 비교하면 기기 시계가 틀어진 사용자에게
    --   마감된 설문이 열려 보이고, 제출했다가 거절당한다.
    opens_at         TIMESTAMPTZ NOT NULL,
    closes_at        TIMESTAMPTZ NOT NULL,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_survey_period CHECK (closes_at > opens_at),

    -- 범위와 대상이 어긋나면 아무에게도 안 보이거나, 어느 기준으로 보여줄지가 갈린다
    CONSTRAINT ck_survey_target CHECK (
        (scope = 'ALL'    AND academy_id IS NULL     AND class_master_id IS NULL)
     OR (scope = 'BRANCH' AND academy_id IS NOT NULL AND class_master_id IS NULL)
     OR (scope = 'CLASS'  AND academy_id IS NOT NULL AND class_master_id IS NOT NULL)
    )
);

CREATE INDEX idx_survey_feed
    ON survey (year, academy_id, closes_at DESC)
    WHERE is_deleted = FALSE;

CREATE TABLE survey_question (
    id               BIGSERIAL   PRIMARY KEY,
    survey_id        BIGINT      NOT NULL REFERENCES survey (id),

    -- 표시 순서. 화면이 문항 순서를 바꿀 수 있어야 한다
    seq              SMALLINT    NOT NULL,

    question_type    VARCHAR(20) NOT NULL
        CHECK (question_type IN ('SINGLE_CHOICE', 'MULTI_CHOICE', 'TEXT', 'NUMBER')),

    title            VARCHAR(300) NOT NULL,
    required         BOOLEAN     NOT NULL DEFAULT TRUE,

    -- NUMBER 문항 범위(가채점 점수 등). NULL이면 제한 없다
    min_value        NUMERIC(10, 2),
    max_value        NUMERIC(10, 2),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_survey_question_seq UNIQUE (survey_id, seq),
    CONSTRAINT ck_survey_question_range CHECK (
        min_value IS NULL OR max_value IS NULL OR max_value >= min_value
    )
);

CREATE INDEX idx_survey_question_survey ON survey_question (survey_id);

CREATE TABLE survey_question_option (
    id               BIGSERIAL   PRIMARY KEY,
    question_id      BIGINT      NOT NULL REFERENCES survey_question (id),
    seq              SMALLINT    NOT NULL,
    label            VARCHAR(200) NOT NULL,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_survey_option_seq UNIQUE (question_id, seq)
);

CREATE INDEX idx_survey_option_question ON survey_question_option (question_id);

-- ★ 중복 제출 방지는 여기서 한다 — 응답 본문과 분리한 이유가 익명 설문이다.
--   익명인데 응답 행에 응답자를 달아두면 이름만 안 보일 뿐 DB에는 누가 뭘 냈는지
--   그대로 남는다. 그건 익명이 아니다. 참여 사실만 여기 남기고,
--   익명 설문의 응답 행은 응답자를 비운다.
CREATE TABLE survey_participant (
    id               BIGSERIAL   PRIMARY KEY,
    survey_id        BIGINT      NOT NULL REFERENCES survey (id),
    enrollment_id    BIGINT      NOT NULL REFERENCES student_enrollment (id),
    submitted_at     TIMESTAMPTZ NOT NULL,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 재제출을 막는다. soft delete를 감안해 부분 유니크로 둔다
CREATE UNIQUE INDEX uq_survey_participant
    ON survey_participant (survey_id, enrollment_id)
    WHERE is_deleted = FALSE;

CREATE TABLE survey_response (
    id               BIGSERIAL   PRIMARY KEY,
    survey_id        BIGINT      NOT NULL REFERENCES survey (id),

    -- ★ 익명 설문이면 NULL이다. 중복 제출은 survey_participant가 막는다
    enrollment_id    BIGINT      REFERENCES student_enrollment (id),
    submitted_at     TIMESTAMPTZ NOT NULL,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_survey_response_survey ON survey_response (survey_id)
    WHERE is_deleted = FALSE;
CREATE INDEX idx_survey_response_enrollment ON survey_response (enrollment_id)
    WHERE is_deleted = FALSE;

CREATE TABLE survey_answer (
    id               BIGSERIAL   PRIMARY KEY,
    response_id      BIGINT      NOT NULL REFERENCES survey_response (id),
    question_id      BIGINT      NOT NULL REFERENCES survey_question (id),

    -- 선택형은 option_id, 주관식은 text_value, 숫자형은 number_value를 쓴다.
    -- 복수선택은 고른 개수만큼 행이 생긴다
    option_id        BIGINT      REFERENCES survey_question_option (id),
    text_value       TEXT,
    number_value     NUMERIC(10, 2),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,

    -- 셋 중 정확히 하나만 채운다. 비어 있는 답이 저장되면 집계에서 조용히 빠진다
    CONSTRAINT ck_survey_answer_value CHECK (
        (option_id IS NOT NULL)::int
      + (text_value IS NOT NULL)::int
      + (number_value IS NOT NULL)::int = 1
    )
);

CREATE INDEX idx_survey_answer_response ON survey_answer (response_id);
-- 집계가 문항별로 훑는다
CREATE INDEX idx_survey_answer_question ON survey_answer (question_id, option_id);

COMMENT ON TABLE survey IS '설문. 일반·가채점이 같은 구조를 쓴다';
COMMENT ON COLUMN survey.anonymous IS
    '익명이면 응답 행에 응답자를 남기지 않는다. 참여 여부만 survey_participant에 남는다';
COMMENT ON TABLE survey_participant IS
    '중복 제출 방지 전용. 익명 설문에서 응답 본문과 응답자를 분리하기 위해 나눠 뒀다';
COMMENT ON COLUMN survey_response.enrollment_id IS '익명 설문이면 NULL';
COMMENT ON TABLE survey_answer IS '문항당 한 행(복수선택은 고른 수만큼)';
