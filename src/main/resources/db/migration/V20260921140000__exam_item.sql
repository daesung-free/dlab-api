-- 문항 정보 — 채점 탭의 근거 (앱 시안 4.5)
--
-- 한 회차 × 과목 × 문항 한 줄. 연구소 파일 두 개를 합친다.
--   문항분석표 — 정답·배점·단원요소·평가요소(내용영역·행동영역)
--   정답률     — 전국 정답률·선택지별 응답률·변별도
-- 둘을 (정규 과목명, 문항번호)로 잇는다. 과목명 표기가 파일마다 달라(물리학I / 물리학Ⅰ)
-- 정규화한 키(subject_key)를 따로 둔다.
--
-- ★ 학생 정오와 분리한다. 문항 정보는 회차당 ~500줄로 전 학생이 공유하고, 학생 정오는
--   학생마다 따로다. 한 테이블에 두면 전국 정답률이 학생 수만큼 복제된다.
CREATE TABLE exam_item
(
    id                BIGSERIAL PRIMARY KEY,
    exam_master_id    BIGINT        NOT NULL REFERENCES exam_master (id),

    -- 연구소 과목 코드(01 국어 · 03 수학 · 06 영어 · 61 한국사 …) — 선택과목끼리 같다
    subject_code      VARCHAR(10),
    subject_name      VARCHAR(30)   NOT NULL,   -- 표시용 원문(문항분석표 기준)
    subject_key       VARCHAR(30)   NOT NULL,   -- 정규화 키 — 잇는 데만 쓴다
    question_no       SMALLINT      NOT NULL,

    answer            SMALLINT,                 -- 정답 번호
    points            SMALLINT,                 -- 배점
    elective          BOOLEAN       NOT NULL DEFAULT FALSE,   -- 선택과목 문항인가

    unit_code         VARCHAR(10),              -- 단원요소
    unit_name         VARCHAR(50),              -- 내용영역(지문·단원)
    skill_code        VARCHAR(10),              -- 평가요소
    skill_name        VARCHAR(50),              -- 행동영역(사실적 이해·추론적 이해 …)

    -- 정답률 파일. 퍼센트 값 그대로(94.4 = 94.4%)
    national_rate     NUMERIC(5, 2),
    choice1_rate      NUMERIC(5, 2),
    choice2_rate      NUMERIC(5, 2),
    choice3_rate      NUMERIC(5, 2),
    choice4_rate      NUMERIC(5, 2),
    choice5_rate      NUMERIC(5, 2),
    discrimination    NUMERIC(6, 3),            -- 변별도(전체)

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_exam_item
    ON exam_item (exam_master_id, subject_key, question_no)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE exam_item IS '회차별 문항 정보(문항분석표+정답률). 전 학생 공유 — 학생 정오와 분리한다';
COMMENT ON COLUMN exam_item.subject_key IS '정규화 과목 키(공백 제거·로마숫자→아라비아). 파일마다 표기가 달라 이걸로 잇는다';
