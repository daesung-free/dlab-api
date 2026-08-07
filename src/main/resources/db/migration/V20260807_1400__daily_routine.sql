-- ==========================================================================
-- 데일리 루틴 (F-4.11-1 관리 · 앱 A-11 표시)
--
--   daily_routine         월별 루틴/테스트 세팅 (과목·배점·권장 여부)
--   daily_routine_result  학생별 결과 (가채점 / 교사 검수 분리)
--
-- ★ 오프라인 시험지 기반이다 — 현장 배부 → 학생 가채점 → 교사 검수 → 웹 입력 → 앱 노출.
--   앱에는 채점·입력 UI가 없다(A-11 "조회 전용").
-- ==========================================================================


-- ==========================================================================
-- 1. 루틴 세팅
-- ==========================================================================

-- ★ 월 단위다. 시트가 "월별 루틴/테스트 세팅 + 전월 복사"를 요구한다 —
--   매달 같은 구성을 반복하므로 복사가 기본 흐름이고, 그러려면 월이 축이어야 한다.
CREATE TABLE daily_routine (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    -- 1~12. year와 함께 "어느 달 구성인가"를 정한다.
    month         SMALLINT    NOT NULL CHECK (month BETWEEN 1 AND 12),
    -- 반별 운영. NULL이면 지점 공통 — 반마다 다른 시험지를 쓰는 경우가 있다.
    class_id      BIGINT      REFERENCES class_master (id),
    name          VARCHAR(100) NOT NULL,
    subject       VARCHAR(30),
    -- 만점. 완료/미완료만 보는 항목은 0으로 둔다.
    max_score     SMALLINT    NOT NULL DEFAULT 0 CHECK (max_score >= 0),
    -- 권장 항목은 앱에서 강조 표시된다(A-11 "권장 항목 강조").
    recommended   BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order    SMALLINT    NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 전월 복사 추적. NULL이면 신규 생성분이다.
    copied_from_id BIGINT     REFERENCES daily_routine (id)
);

COMMENT ON TABLE daily_routine IS
    '월별 데일리 루틴/테스트 세팅. 전월 복사가 기본 흐름이라 월이 축이다.';
COMMENT ON COLUMN daily_routine.max_score IS '만점. 0이면 점수 없이 완료/미완료만 본다.';
COMMENT ON COLUMN daily_routine.recommended IS '앱에서 강조 표시(A-11 "권장 항목 강조").';

CREATE INDEX idx_daily_routine_month
    ON daily_routine (academy_id, year, month, sort_order) WHERE is_deleted = FALSE;


-- ==========================================================================
-- 2. 결과
-- ==========================================================================

-- ★ 상태가 흐름이다: 예정 → 배부 → 제출 → 검수완료 → 앱노출.
--   시트가 이 순서를 명시했고, 갈래로 미제출·결시가 있다.
--
-- ★★ 가채점 점수와 교사 검수 점수를 분리 보관한다(시트 명시).
--   학생이 스스로 매긴 점수와 교사가 확인한 점수가 다를 수 있고, 그 차이 자체가
--   확인 대상이다. 한 칸에 덮어쓰면 "학생이 몇 점이라고 했는지"가 사라진다.
CREATE TABLE daily_routine_result (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    routine_id     BIGINT      NOT NULL REFERENCES daily_routine (id),
    enrollment_id  BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 어느 날짜분인가. 루틴은 월 단위 세팅이고 결과는 일 단위로 쌓인다.
    result_date    DATE        NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PLANNED'
                   CHECK (status IN ('PLANNED', 'DISTRIBUTED', 'SUBMITTED',
                                     'REVIEWED', 'PUBLISHED', 'NOT_SUBMITTED', 'ABSENT')),
    -- 학생이 스스로 매긴 점수. 교사 검수 전 값이다.
    self_score     SMALLINT    CHECK (self_score IS NULL OR self_score >= 0),
    -- 교사가 확인한 점수. 이 값이 통계·상벌점의 기준이다.
    reviewed_score SMALLINT    CHECK (reviewed_score IS NULL OR reviewed_score >= 0),
    reviewed_at    TIMESTAMPTZ,
    memo           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 같은 학생·같은 루틴·같은 날짜에 결과는 하나다.
    CONSTRAINT uq_daily_routine_result UNIQUE (routine_id, enrollment_id, result_date)
);

COMMENT ON TABLE daily_routine_result IS
    '학생별 루틴 결과. 상태 흐름 예정→배부→제출→검수완료→앱노출(+미제출·결시).';
COMMENT ON COLUMN daily_routine_result.self_score IS
    '학생 가채점. 교사 검수 점수와 분리 보관한다 — 덮어쓰면 "학생이 몇 점이라 했는지"가 사라진다.';
COMMENT ON COLUMN daily_routine_result.reviewed_score IS
    '교사 검수 점수. 통계·상벌점의 기준값이다.';

-- 앱은 "오늘 내 루틴"을 본다. 학생·날짜가 선행 조건이다.
CREATE INDEX idx_routine_result_student
    ON daily_routine_result (enrollment_id, result_date) WHERE is_deleted = FALSE;

-- 관리자는 "이 루틴의 반 단위 그리드"를 본다.
CREATE INDEX idx_routine_result_routine
    ON daily_routine_result (routine_id, result_date) WHERE is_deleted = FALSE;
