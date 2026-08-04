-- V3: 공휴일 마스터
--
-- 급식 가능일(MealPolicy = 주말 + 공휴일 제외) 계산의 기준 데이터다.
-- Phase 0 공통모듈이며 급식·학습계획·출결 배치가 모두 여기에 의존한다.
--
-- ★ 음력 공휴일(설날·추석·석가탄신일)을 코드로 계산하지 않고 데이터로 넣는 이유:
--   ① 음력→양력 변환은 라이브러리마다 결과가 갈리고 검증이 어렵다.
--   ② 대체공휴일 규칙이 해마다 바뀐다(적용 대상 공휴일이 계속 추가돼 왔다).
--   ③ 임시공휴일은 규칙 자체가 없다 — 정부가 그때그때 지정한다.
--   공공데이터포털 특일정보 API를 붙이더라도 결국 이 테이블에 적재하는 구조가 맞다.
--   그래야 API 장애 시에도 급식 신청이 멈추지 않는다.

CREATE TABLE holiday (
    id           BIGSERIAL   PRIMARY KEY,
    -- NULL이면 전 지점 공통(법정공휴일). 지점 고유 휴일(개원기념일 등)만 값을 채운다.
    academy_id   BIGINT      REFERENCES academy (id),
    holiday_date DATE        NOT NULL,
    name         VARCHAR(50) NOT NULL,
    holiday_type VARCHAR(20) NOT NULL
                 CHECK (holiday_type IN ('PUBLIC', 'SUBSTITUTE', 'TEMPORARY', 'ACADEMY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE holiday IS '공휴일 마스터. 급식 가능일 계산의 기준';
COMMENT ON COLUMN holiday.academy_id IS 'NULL이면 전 지점 공통(법정공휴일). 값이 있으면 그 지점만';
COMMENT ON COLUMN holiday.holiday_type IS
    'PUBLIC 법정공휴일 / SUBSTITUTE 대체공휴일 / TEMPORARY 임시공휴일 / ACADEMY 학원 자체휴일';

-- 전 지점 공통 휴일은 날짜당 하나.
-- academy_id가 NULL이면 일반 UNIQUE로는 중복이 막히지 않으므로(NULL끼리는 서로 다르게 취급)
-- 부분 인덱스로 나눠 건다.
CREATE UNIQUE INDEX uq_holiday_nationwide
    ON holiday (holiday_date) WHERE academy_id IS NULL AND NOT is_deleted;

CREATE UNIQUE INDEX uq_holiday_academy
    ON holiday (academy_id, holiday_date) WHERE academy_id IS NOT NULL AND NOT is_deleted;

-- 기간 조회(급식 신청 월 단위)가 지배적이다
CREATE INDEX idx_holiday_date ON holiday (holiday_date) WHERE NOT is_deleted;
