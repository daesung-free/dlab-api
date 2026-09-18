-- 실적 관리 = 수시/정시 지원대학과 그 결과 (F-4.10-6)
--
-- ★ 지원과 실적을 두 테이블로 나누지 않는다.
--   지원한 대학 목록에 합불이 붙으면 그게 곧 실적이다. 따로 두면 같은 대학을 두 번
--   입력하게 되고, 둘이 어긋나면 어느 쪽이 맞는지 알 수 없다.
--
-- ★ 입력 주체를 남긴다.
--   0826 회신이 성적 입력을 "처음 입력시 학생, 이후 수정시에는 직원을 통해서" 로 확정했고
--   지원대학도 같은 모양이다. 누가 넣은 값인지 남지 않으면 직원 확인을 거친 값과
--   학생이 적어낸 값이 섞인다.
CREATE TABLE admission_result
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT     NOT NULL,
    academy_id      BIGINT       NOT NULL REFERENCES academy (id),

    -- 그 해 입시 결과라 사람이 아니라 등록 건에 붙는다 (docs/entity-design.md O)
    enrollment_id   BIGINT       NOT NULL REFERENCES student_enrollment (id),

    -- ★ 수시/정시. 개수 제한이 6개·3개로 다르고 통계도 이 축으로 나뉜다
    admission_type  VARCHAR(10)  NOT NULL CHECK (admission_type IN ('EARLY', 'REGULAR')),

    university_name VARCHAR(100) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    -- 전형명. 매년 바뀌어 마스터를 두지 않고 문자열로 받는다
    track_name      VARCHAR(100),

    -- ★ 3종이다. 불합격과 "합격했는데 등록 안 함" 은 실적에서 완전히 다른 숫자다
    result          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (result IN ('PENDING', 'PASSED', 'FAILED', 'GAVE_UP')),

    -- 학생이 적어낸 값인지 직원이 확인한 값인지
    source          VARCHAR(10)  NOT NULL DEFAULT 'STAFF'
        CHECK (source IN ('STUDENT', 'STAFF')),

    memo            VARCHAR(500),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_admission_result_enrollment
    ON admission_result (enrollment_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_admission_result_scope
    ON admission_result (academy_id, year, admission_type) WHERE is_deleted = FALSE;

COMMENT ON TABLE admission_result IS '수시/정시 지원대학과 결과. 지원과 실적을 한 행으로 둔다';
COMMENT ON COLUMN admission_result.result IS 'PENDING 은 아직 발표 전이다 — 불합격과 구분해야 집계가 맞는다';
COMMENT ON COLUMN admission_result.source IS '학생이 적어낸 값인지 직원이 확인한 값인지';
