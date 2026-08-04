-- 교시 마스터에 요일 구분·유형 추가
--
-- ★ 파일명이 타임스탬프인 이유: V1~V4까지는 순차 번호였으나, 두 사람이 며칠씩 로컬에
--   쌓다가 푸시하는 작업 방식이라 "만들기 전 pull"로는 같은 번호를 막을 수 없다.
--   파일명이 다르면 Git은 충돌로 보지 않아 조용히 머지되고, 기동할 때서야
--   "Found more than one migration with version N"으로 터진다.
--   타임스탬프는 동시에 만들어도 겹치지 않는다. 기존 V1~V4는 그대로 둔다.
--
-- ─────────────────────────────────────────────────────────────
-- 왜 필요한가: 주말은 교시 구성이 다르다
--
-- 시안 plan-3(토요일)은 0교시·2교시가 없다. 지금 스키마는 (academy_id, year, period_no)만
-- 유일해서 평일과 주말 시간표를 동시에 담을 수 없다.
--
-- 그대로 두면 DSA setAttendStd가 토요일마다 전원 code 113("시간표 없어 자동판별 불가")으로
-- 떨어진다 — 학생이 매주 토요일에 하원/외출을 직접 골라야 한다.
-- ─────────────────────────────────────────────────────────────

ALTER TABLE period_master
    ADD COLUMN day_type VARCHAR(10) NOT NULL DEFAULT 'WEEKDAY'
        CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY'));

-- 교시 유형. 출결 판정이 이 값을 본다 —
-- 점심시간 태깅은 code 122("학습 시간이 아닙니다")로 거부해야 한다.
ALTER TABLE period_master
    ADD COLUMN period_type VARCHAR(20) NOT NULL DEFAULT 'CLASS'
        CHECK (period_type IN ('CLASS', 'SELF_STUDY', 'MEAL', 'BREAK', 'ETC'));

-- 학습계획 입력 가능 여부. 0723 피드백으로 점심·저녁 시간대도 입력을 허용하게 됐다.
ALTER TABLE period_master
    ADD COLUMN planable BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN period_master.day_type IS '요일 구분. 주말은 교시 구성이 다르다(시안 plan-3)';
COMMENT ON COLUMN period_master.period_type IS
    'CLASS 수업 / SELF_STUDY 야자 / MEAL 점심·저녁 / BREAK 간식 / ETC 종례. 출결 code 122 판정 근거';
COMMENT ON COLUMN period_master.planable IS '학습계획 입력 허용 여부(0723: 점심·저녁도 허용)';

-- 요일별로 같은 교시 번호가 존재하므로 유일성 범위를 넓힌다.
ALTER TABLE period_master DROP CONSTRAINT uq_period_master;
ALTER TABLE period_master
    ADD CONSTRAINT uq_period_master UNIQUE (academy_id, year, day_type, period_no);

-- 출결 판정은 "지금 시각이 어느 교시인가"를 매 태깅마다 조회한다.
CREATE INDEX idx_period_master_lookup
    ON period_master (academy_id, year, day_type, start_time)
    WHERE NOT is_deleted;
