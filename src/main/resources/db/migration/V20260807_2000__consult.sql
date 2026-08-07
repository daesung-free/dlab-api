-- V20260807_2000: 상담 일지 (F-4.11-4)
--
-- ★ DSA에 대응 화면이 없다 — 구글시트로 운영하던 것을 시스템화하는 것이다.
--
-- ★ 이번 범위는 일지까지다. 상담 리포트(학부모용 요약)는 다른 도메인 셋에 얹혀 있어
--   지금 만들 수 없다:
--     · 성적 상세 결합       → F-4.6-1 (E-2·I-11·S-2 대기)
--     · 과목별 이행률(별점)  → F-4.11-2 학습계획 (I-19 대기, 표기 정합도 미확정)
--     · 신상기록부 작성 여부 → F-4.11-8 (I-17 대기)

-- ─────────────────────────────────────────────────────────────
-- 상담 항목 태그 (마스터)
--
-- ★ 자유 서술로 두면 표현이 사람마다 갈려 집계가 안 된다.
--   "성적 하락 걱정" / "성적이 떨어져 고민" / "성적저하"가 전부 다른 문자열이 된다.
--   관리자가 항목을 미리 만들고 담임은 고르기만 한다.
--
-- ★ 목록 자체는 아직 못 받았다(상위 유형 5종만 확정). 데이터로 두었으므로
--   확정되면 행만 넣으면 된다 — penalty_rule과 같은 방식이다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE consult_tag (
    id           BIGSERIAL   PRIMARY KEY,
    academy_id   BIGINT      NOT NULL REFERENCES academy (id),
    year         SMALLINT    NOT NULL,

    -- 어느 상담 유형에 붙는 태그인지. NULL이면 모든 유형에서 쓴다
    consult_type VARCHAR(20)
        CHECK (consult_type IN ('REGULAR','SCORE','LIFE','ADMISSION','PARENT')),
    name         VARCHAR(50) NOT NULL,
    sort_order   SMALLINT    NOT NULL DEFAULT 0,
    -- 화면에 한 번에 노출할 최대 개수(시트 max_display). 태그가 쌓이면 담임이 못 찾는다
    max_display  SMALLINT    NOT NULL DEFAULT 10,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_consult_tag
    ON consult_tag (academy_id, year, consult_type, name) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 상담 일지
-- ─────────────────────────────────────────────────────────────
CREATE TABLE consult_log (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 상담한 사람. 담당선생님(teacher)이다 — 행정은 상담을 하지 않는다
    teacher_id    BIGINT      REFERENCES teacher (id),

    consult_type  VARCHAR(20) NOT NULL
        CHECK (consult_type IN ('REGULAR','SCORE','LIFE','ADMISSION','PARENT')),
    -- 대면 / 전화 / 온라인 (화면 방식 3종)
    method        VARCHAR(20) NOT NULL DEFAULT 'FACE'
        CHECK (method IN ('FACE','PHONE','ONLINE')),

    consulted_at  DATE        NOT NULL,
    -- "20분 · 상담실 2" 같은 자유 입력(화면 그대로)
    place_note    VARCHAR(100),

    content       TEXT        NOT NULL,
    -- 학생과 합의한 실행 계획. 다음 상담에서 이행을 확인한다
    action_plan   TEXT,
    action_done   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 다음 상담 예정일. 현황 화면이 "D-1 예정 / 지연 3일"을 이걸로 계산한다
    next_due_date DATE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 학생 상세가 최근 순으로 편다
CREATE INDEX idx_consult_log_enrollment
    ON consult_log (enrollment_id, consulted_at DESC) WHERE is_deleted = FALSE;
-- 담임별·반별 현황 조회
CREATE INDEX idx_consult_log_lookup
    ON consult_log (academy_id, year, consulted_at DESC) WHERE is_deleted = FALSE;

-- 일지 ↔ 태그 (다대다)
CREATE TABLE consult_log_tag (
    id         BIGSERIAL PRIMARY KEY,
    log_id     BIGINT    NOT NULL REFERENCES consult_log (id),
    tag_id     BIGINT    NOT NULL REFERENCES consult_tag (id),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_consult_log_tag ON consult_log_tag (log_id, tag_id);

COMMENT ON TABLE consult_tag IS
    '상담 항목 마스터. 자유 서술로 두면 표현이 갈려 집계가 안 된다';
COMMENT ON COLUMN consult_tag.max_display IS '화면 노출 상한(시트). 태그가 쌓이면 담임이 못 찾는다';
COMMENT ON TABLE consult_log IS '상담 일지. 리포트(학부모 요약)는 성적·학습계획·신상기록부 대기';
COMMENT ON COLUMN consult_log.next_due_date IS '다음 상담 예정일. 현황이 "지연 N일"을 이걸로 센다';
