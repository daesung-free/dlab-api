-- 학생 정오·답안 (채점 탭 2/3)
--
-- ★ 학생 × 문항 한 줄이 아니라 학생 × 과목 한 줄이다.
--   문항 한 줄씩이면 한 회차에 약 38만 행(11개 지점 × 학생 170명 × 200문항)이고 매월
--   쌓인다. 과목 단위로 묶으면 학생당 8행 정도다. 문항은 first_no 부터 순서대로 이어 붙인다.
--
-- ★ 국어·수학은 한 영역이 과목 두 개로 갈린다 — 1~34번은 공통(국어), 35~45번은 선택
--   (언어와매체). 채점 기준(exam_item)이 과목별이라 행도 과목별로 나눈다.
CREATE TABLE student_item_response
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT     NOT NULL,
    academy_id      BIGINT       NOT NULL REFERENCES academy (id),
    enrollment_id   BIGINT       NOT NULL REFERENCES student_enrollment (id),
    exam_master_id  BIGINT       NOT NULL REFERENCES exam_master (id),

    subject_key     VARCHAR(30)  NOT NULL,   -- exam_item.subject_key 와 같은 정규 키
    first_no        SMALLINT     NOT NULL,   -- 이 행이 시작하는 문항 번호

    -- 문항마다 한 글자: O 맞음 · X 틀림 · - 비었음
    results         VARCHAR(60),
    -- ★ 쉼표 구분. 수학 단답형 정답이 세 자리(예: 125)라 한 글자씩 붙일 수 없다
    answers         VARCHAR(400),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_student_item_response
    ON student_item_response (enrollment_id, exam_master_id, subject_key)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE student_item_response IS
    '학생 정오·답안. 학생×과목 한 줄에 문항을 first_no 부터 이어 붙인다(학생×문항이면 회차당 38만 행)';
