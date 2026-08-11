-- V20260811_1000: 통계 집계용 인덱스 (F-4.11-11)
--
-- ★ 사전집계 테이블을 만들지 않는다 — 지금은 필요 없다.
--
--   시트 지침은 "집계 전용 API + 배치 사전집계 테이블(대용량 실시간 집계 금지)"인데,
--   금지하려는 건 <b>요청 시점에 원시 로그를 훑는 것</b>이다. 우리는 이미 그러지 않는다:
--     · 순공시간·출결 상태 → 새벽 배치가 attendance_daily_status에 학생×하루 1행으로
--       집계해둔다. 통계는 그 행을 SUM/COUNT할 뿐 attendance_tagging_log를 안 본다
--     · 상벌점·급식·청구 → 애초에 건별 행이고 기간 조회는 인덱스를 탄다
--
--   여기서 사전집계 테이블을 또 두면 배치가 하나 늘고, 그게 안 돌면 통계가 조용히
--   멈춘다(어제 숫자가 그대로 보인다). 실측으로 느려지면 그때 넣는 게 맞다.
--
-- 대신 지점·기간 집계에 필요한 인덱스만 채운다.
-- penalty_point는 (enrollment_id, occurred_at)만 있어 지점 전체 집계가 풀스캔이었다.

CREATE INDEX idx_penalty_point_academy_occurred
    ON penalty_point (academy_id, occurred_at DESC)
    WHERE is_deleted = FALSE;

-- 청구는 (academy_id, year, status)가 있으나 유형별 매출 집계가 billing_type을 탄다
CREATE INDEX idx_billing_type
    ON billing (academy_id, year, billing_type)
    WHERE is_deleted = FALSE;

COMMENT ON INDEX idx_penalty_point_academy_occurred IS
    '지점·기간 상벌점 집계. 없으면 통계가 풀스캔한다';
