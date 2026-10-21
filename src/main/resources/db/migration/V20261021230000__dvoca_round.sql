-- D:VOCA 회차 일정 (v1 5장 · v2 착수 순서 4).
--
-- ★★ 일정을 미리 만들어 두지 않는다 (v1 5.6)
--   "지점 시작일 + 시험 없는 날 목록 + 회독 규칙으로 계산하는 쪽을 권장한다.
--    시험 없는 날이 나중에 추가되면 이후 일정이 모두 밀려야 하기 때문이다."
--
--   그래서 이 스키마에는 「예정된 회차」가 없다. 들고 있는 것은 셋뿐이다.
--     ① 지점 설정 (시작일)
--     ② 시험 없는 날
--     ③ ★ 이미 공개된 회차 — 공개 시점에 범위를 확정 저장한다
--
--   "이미 공개된 회차의 범위는 공개 시점에 확정 저장한다. 나중에 시험 없는 날을
--    추가해도 지난 회차와 지난 응시 기록이 바뀌지 않아야 한다."

-- ─────────────────────────────────────────────────────────────
-- 지점 설정
CREATE TABLE vocab_schedule_config
(
    id         BIGSERIAL PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    start_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_vocab_schedule_config
    ON vocab_schedule_config (academy_id, year) WHERE is_deleted = FALSE;

COMMENT ON COLUMN vocab_schedule_config.start_date IS
    '1회독 1유닛이 공개되는 첫 시험일. ★ 지점마다 따로 돈다(v1 5.1)';

-- ─────────────────────────────────────────────────────────────
-- 시험 없는 날
--
-- ★ holiday 테이블을 쓰지 않는다. 그건 급식 가능일용이라 법정공휴일이 전부 들어가고,
--   여기 필요한 것은 「모의고사 때문에 시험을 안 보는 날」까지 포함한 운영 지정이다
--   (v1 5.1: "공휴일, 모의고사 등 — 지점 관리자가 날짜로 지정한다").
--   같은 이유로 교습일수도 holiday 를 쓰지 않는다(CLAUDE.md).
CREATE TABLE vocab_no_exam_day
(
    id         BIGSERIAL PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    exam_date  DATE        NOT NULL,
    reason     VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_vocab_no_exam_day
    ON vocab_no_exam_day (academy_id, exam_date) WHERE is_deleted = FALSE;

COMMENT ON TABLE vocab_no_exam_day IS
    '시험 없는 날. 그날은 회차가 공개되지 않고 진도가 다음 시험일로 밀린다';

-- ─────────────────────────────────────────────────────────────
-- 공개된 회차 — ★ 공개 시점에 범위를 확정 저장한다
CREATE TABLE vocab_exam_round
(
    id         BIGSERIAL PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    round_no   INTEGER     NOT NULL,
    cycle      SMALLINT    NOT NULL CHECK (cycle BETWEEN 1 AND 3),
    open_date  DATE        NOT NULL,
    weekly     BOOLEAN     NOT NULL DEFAULT FALSE,
    unit_from  SMALLINT    NOT NULL CHECK (unit_from BETWEEN 1 AND 50),
    unit_to    SMALLINT    NOT NULL CHECK (unit_to BETWEEN 1 AND 50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 지점·날짜에 평일분과 금요일 누적이 함께 올 수 있다 — weekly 로 가른다
CREATE UNIQUE INDEX uq_vocab_exam_round
    ON vocab_exam_round (academy_id, year, open_date, weekly) WHERE is_deleted = FALSE;
CREATE INDEX ix_vocab_exam_round_open
    ON vocab_exam_round (academy_id, open_date) WHERE is_deleted = FALSE;

COMMENT ON TABLE vocab_exam_round IS
    '★ 공개된 회차만 들어온다. 예정 회차는 저장하지 않고 계산한다(v1 5.6) — '
    '시험 없는 날이 나중에 추가되면 이후 일정이 전부 밀려야 하기 때문이다';
COMMENT ON COLUMN vocab_exam_round.weekly IS
    '금요일 누적. ★ 범위는 그 주에 **실제로 공개된** 유닛 전체다 — '
    '공휴일로 밀리면 그만큼 줄어든다(v1 5.2 업체 예시 2주차: 월5·화6·수7·목공휴일 → 금 5-7)';
COMMENT ON COLUMN vocab_exam_round.cycle IS
    '회독. 1=하루 1유닛(50회차) · 2=하루 2유닛(25회차) · 3=하루 5유닛(10회차). '
    '⚠️ 3회독 이후 동작은 미확정이다(v1 Q4) — 지금은 정지한다';
