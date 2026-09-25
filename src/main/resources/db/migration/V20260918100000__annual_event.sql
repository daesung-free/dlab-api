-- 연간 행사 (F-4.11-10)
--
-- ★ 학습계획에 복사해 넣지 않는다.
--   "행사를 학습계획에 반영" 을 학생별 계획 행으로 복사하면, 행사를 수정·삭제할 때
--   이미 복사된 수백 행을 따라다니며 고쳐야 한다. 하나라도 놓치면 없어진 행사가
--   학생 화면에만 남는다. 조회 시 날짜로 합쳐 내리면 수정·삭제가 그대로 반영된다.
--
-- ★ 공휴일(holiday)과 다르다.
--   공휴일은 "쉬는 날"이라 급식·교습일수 계산에 쓰이고, 행사는 "그날 무슨 일이 있다"는
--   표시다. 개교기념일처럼 둘 다인 날은 양쪽에 각각 등록한다 — 합치면 행사를 지웠는데
--   급식이 열리는 일이 생긴다.
CREATE TABLE annual_event
(
    id          BIGSERIAL PRIMARY KEY,
    year        SMALLINT     NOT NULL,

    -- NULL 이면 전 지점 공통. 지점 행사면 그 지점만 (holiday 와 같은 규약)
    academy_id  BIGINT       REFERENCES academy (id),

    name        VARCHAR(100) NOT NULL,

    -- 하루짜리도 start = end 로 넣는다. 기간 행사(수련회 3일 등)가 실재한다
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL,

    event_type  VARCHAR(20)  NOT NULL DEFAULT 'ACADEMY'
        CHECK (event_type IN ('ACADEMY', 'EXAM', 'HOLIDAY_EVENT', 'ETC')),

    -- 학습계획·달력에 띄울지. 내부 일정은 등록만 하고 학생에게 안 보일 수 있다
    show_in_plan BOOLEAN     NOT NULL DEFAULT TRUE,

    memo        VARCHAR(500),

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_annual_event_period CHECK (end_date >= start_date)
);

CREATE INDEX idx_annual_event_period
    ON annual_event (year, start_date, end_date)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE annual_event IS '연간 행사. 학습계획에는 복사하지 않고 조회 시 날짜로 합쳐 내린다';
COMMENT ON COLUMN annual_event.academy_id IS 'NULL 이면 전 지점 공통';
COMMENT ON COLUMN annual_event.show_in_plan IS '학습계획·달력 노출 여부. 내부 일정은 FALSE';
