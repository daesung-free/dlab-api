-- V20260827_1000: 장학 취소 판정 (0820 규정 · 방식 2026-08-26 승인)
--
-- ★ 자동으로 취소하지 않는다. "검토 대상"으로 띄우고 사람이 확정한다.
--   시트가 *"개인사정에 의해 응시를 못할 경우 더프모 성적으로 대체하는 경우도 있다"*,
--   *"지점 상황이나 개인별 사정에 의해 예외를 두는 경우가 많이 발생한다"*고 명시했다.
--   자동 확정하면 예외인 학생 장학금이 조용히 날아간다.
--   클라이언트도 이 방식으로 하자고 회신했다(2026-08-26).
--
-- ★ 기준을 코드에 박지 않고 데이터로 둔다 — penalty_rule 과 같은 방식이다.
--   시트에 *"지점별, 연도별 상이 할수 있음"*이라고 되어 있고, 요건 셋 중 하나는
--   지금 판정할 수단이 아예 없다(아래).

-- ─────────────────────────────────────────────────────────────
-- 1. 취소 기준
--
-- 요건 세 가지 (0820 규정):
--   · 벌점 누적      40점 이상 — 제적 기준과 동일하다
--   · 성적 미충족    6월 or 9월 평가원 3과목 등급합 5
--   · 모의고사 미응시 더프리미엄 2회 미응시
--
-- ⚠️ active 기본값이 FALSE 다. 행을 넣어도 명시적으로 켜기 전엔 안 돈다 —
--    미검증 규칙이 실수로 도는 것을 막는다(penalty_rule 과 같은 판단).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE scholarship_cancel_rule (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      REFERENCES academy (id),   -- NULL = 전 지점 공통
    year          SMALLINT    NOT NULL,

    rule_type     VARCHAR(30) NOT NULL
        CHECK (rule_type IN ('PENALTY_POINT', 'EXAM_GRADE_SUM', 'MOCK_EXAM_ABSENCE')),

    -- 임계값. 벌점 40 / 등급합 5 / 미응시 2회
    threshold     INTEGER     NOT NULL CHECK (threshold >= 0),

    -- ★ 등급합 대상 과목. 규정에 "3과목"이라고만 적혀 있고 어느 과목인지가 없다.
    --   국·수·영으로 추정하나 확인 대기 중이라 EXAM_GRADE_SUM 규칙은 꺼둔 채로 둔다.
    --   콤마 구분(KOREAN,MATH,ENGLISH). exam_subject.subject_code 와 같은 값이다
    subject_codes VARCHAR(200),

    active        BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 규칙이 두 벌이면 어느 임계값이 진짜인지 알 수 없다.
-- academy_id 가 NULL 일 수 있어 부분 인덱스를 나눈다(PostgreSQL 은 NULL 을 서로 다르게 본다)
CREATE UNIQUE INDEX uq_scholarship_rule_common
    ON scholarship_cancel_rule (year, rule_type)
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_scholarship_rule_academy
    ON scholarship_cancel_rule (academy_id, year, rule_type)
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN scholarship_cancel_rule.subject_codes IS
    '등급합 대상 과목. 규정에 "3과목"이라고만 있어 국·수·영으로 추정 — 확인 대기';
COMMENT ON COLUMN scholarship_cancel_rule.active IS
    '기본 FALSE. 행을 넣어도 켜기 전엔 안 돈다 — 미검증 규칙이 도는 것을 막는다';

-- ─────────────────────────────────────────────────────────────
-- 2. 검토 대상
--
-- ★ 자동 판정 결과이지 취소가 아니다. 사람이 확정해야 취소된다.
--   그래서 status 가 PENDING 으로 시작하고, 예외로 넘길 수도 있다.
--
-- ★ 판정 근거(detected_value)를 남긴다. "왜 걸렸는지"가 없으면 담당자가
--   원본 데이터를 다시 뒤져야 하고, 예외 판단을 할 수 없다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE scholarship_review (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    enrollment_id  BIGINT      NOT NULL REFERENCES student_enrollment (id),

    rule_type      VARCHAR(30) NOT NULL
        CHECK (rule_type IN ('PENALTY_POINT', 'EXAM_GRADE_SUM', 'MOCK_EXAM_ABSENCE')),
    -- 걸린 실제 값. 벌점 45점, 등급합 7 같은 것
    detected_value INTEGER     NOT NULL,
    -- 판정 당시 임계값. 나중에 기준이 바뀌어도 "그때 왜 걸렸는지"가 남아야 한다
    threshold      INTEGER     NOT NULL,
    detail         TEXT,

    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CANCELED', 'EXCEPTED')),
    -- 예외로 넘긴 경우 사유가 남아야 한다 — 나중에 "왜 살려뒀나"에 답해야 한다
    decision_note  TEXT,
    decided_by     BIGINT,
    decided_at     TIMESTAMPTZ,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 같은 학생이 같은 규칙으로 두 번 올라오지 않는다.
--   판정 배치가 여러 번 돌아도 목록이 중복으로 쌓이면 담당자가 같은 건을 반복해서 본다.
--   처리된 건(CANCELED·EXCEPTED)은 다시 올라올 수 있어야 하므로 PENDING 만 막는다
CREATE UNIQUE INDEX uq_scholarship_review_pending
    ON scholarship_review (enrollment_id, year, rule_type)
    WHERE status = 'PENDING' AND is_deleted = FALSE;

CREATE INDEX idx_scholarship_review_list
    ON scholarship_review (academy_id, year, status) WHERE is_deleted = FALSE;

COMMENT ON COLUMN scholarship_review.status IS
    'PENDING=검토 대상, CANCELED=취소 확정, EXCEPTED=예외 인정. 자동으로 CANCELED 가 되지 않는다';

-- ─────────────────────────────────────────────────────────────
-- 3. 2026년 기준 초기 데이터
--
-- ⚠️ 전부 active = FALSE 다. 검증 후 관리자 화면에서 켠다.
--
--   · PENALTY_POINT   — 지금도 판정 가능하다(상벌점 데이터가 있다)
--   · EXAM_GRADE_SUM  — "3과목"이 어느 과목인지 확인 대기. 국·수·영으로 추정해 둔다
--   · MOCK_EXAM_ABSENCE — ⚠️ 판정 수단이 없다. 더프리미엄 응시 이력이 우리 DB에 없어서
--                          E-2(API 명세) 수령 전까지는 켤 수 없다
-- ─────────────────────────────────────────────────────────────
INSERT INTO scholarship_cancel_rule (academy_id, year, rule_type, threshold,
                                     subject_codes, active, created_by)
VALUES
    (NULL, 2026, 'PENALTY_POINT',     40, NULL,                        FALSE, 0),
    (NULL, 2026, 'EXAM_GRADE_SUM',     5, 'KOREAN,MATH,ENGLISH',       FALSE, 0),
    (NULL, 2026, 'MOCK_EXAM_ABSENCE',  2, NULL,                        FALSE, 0);
