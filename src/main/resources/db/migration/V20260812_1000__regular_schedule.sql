-- V20260812_1000: 정기일정 (F-4.1-7, 앱 A-7)
--
-- 현강·과외처럼 매주 같은 요일에 나갔다 오는 외부 일정을 월 단위로 미리 등록한다.
-- 승인되면 그 시간의 외출이 무단이 아니게 되어 벌점을 받지 않는다.
--
-- ★ 두 테이블로 나눈 이유 — 승인이 "월 단위 제출" 단위이기 때문이다.
--   한 학생이 월요일 수학, 목요일 영어를 다닌다면 줄은 둘인데 승인은 한 번이다.
--   줄마다 승인 요청을 만들면 학부모 승인 큐에 같은 학생이 여러 번 뜨고,
--   ApprovalService가 "처리 대기중인 신청이 있습니다"로 두 번째 줄을 막는다.
--
-- ★ 미확정 (I-27) — 관리자 등록분과 학생 등록분의 병합·중복 규칙이 안 정해졌다.
--   그래서 여기서는 어느 쪽을 이기게 하지 않는다. 같은 요일·시간대가 겹치면
--   등록을 거절하고 사람이 판단하게 둔다 — 규칙 없이 자동으로 하나를 지우면
--   담임이 등록한 것이 학생 등록으로 조용히 덮이는 일이 생긴다.

CREATE TABLE regular_schedule (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 대상 월. 매월 1일에 그 달치를 등록한다
    schedule_month SMALLINT   NOT NULL CHECK (schedule_month BETWEEN 1 AND 12),

    -- 누가 등록했나. ADMIN(담임 대신 등록)은 자동 승인된다
    source        VARCHAR(10) NOT NULL CHECK (source IN ('STUDENT','ADMIN')),

    -- 승인 라우팅(F-4.11-5)에 위임한다. ADMIN 등록분은 NULL이고 곧바로 승인 상태다
    approval_request_id BIGINT REFERENCES approval_request (id),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 한 학생이 같은 달에 여러 번 제출하지 못하게 한다. 수정은 기존 제출을 고친다 —
-- 여러 벌이 쌓이면 어느 것이 그달의 일정인지 판정 배치가 고를 수 없다
CREATE UNIQUE INDEX uq_regular_schedule
    ON regular_schedule (enrollment_id, year, schedule_month) WHERE is_deleted = FALSE;

CREATE INDEX idx_regular_schedule_month
    ON regular_schedule (academy_id, year, schedule_month) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 일정 줄
--
-- ★ 요일 반복이다. 날짜를 하나씩 넣지 않는다 — 현강은 "매주 화요일 19시"라
--   한 달치를 날짜로 펼치면 4~5행이 되고, 월이 바뀔 때마다 다시 만들어야 한다.
--   판정은 그날의 요일로 찾는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE regular_schedule_item (
    id          BIGSERIAL   PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES regular_schedule (id),

    -- ISO-8601: 1=월 … 7=일
    day_of_week SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    -- 나가는 시각 / 돌아오는 시각. 이 시각과 실제 태깅을 대조해 인정 여부를 가른다
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,

    -- "○○학원 수학" 같은 표시용 이름
    title       VARCHAR(100) NOT NULL,
    place       VARCHAR(100),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_regular_schedule_item_time CHECK (end_time > start_time)
);

CREATE INDEX idx_regular_schedule_item_schedule
    ON regular_schedule_item (schedule_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE regular_schedule IS
    '정기일정 월 단위 제출(F-4.1-7). 승인은 제출 단위 — 줄마다 걸면 승인 큐가 중복된다';
COMMENT ON COLUMN regular_schedule.source IS
    'STUDENT=앱 등록(승인 필요) / ADMIN=담임 대신 등록(자동 승인)';

-- ─────────────────────────────────────────────────────────────
-- 정기일정 미인정 → 벌점 트리거 추가
--
-- 등록 시각과 실제 출입이 30분 이상 어긋나면 "미인정"이고 벌점 대상이다
-- (요구사항 3시트: ABS(등록 시각 − 실제 출입 시각) ≥ 30분).
--
-- ★ 규칙 자체는 데이터로 둔다(I-5 미확정). 트리거 값만 열어두고 점수·항목은
--   penalty_rule 행이 생기고 active=true가 될 때까지 아무 일도 일어나지 않는다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE penalty_rule DROP CONSTRAINT IF EXISTS penalty_rule_trigger_type_check;

ALTER TABLE penalty_rule
    ADD CONSTRAINT penalty_rule_trigger_type_check
    CHECK (trigger_type IN ('ATTENDANCE', 'DAILY_ROUTINE', 'REGULAR_SCHEDULE'));
