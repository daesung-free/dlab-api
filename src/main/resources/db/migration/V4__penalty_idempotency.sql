-- V4: 상벌점 자동부여 멱등 제약
--
-- V1이 penalty_point.idempotency_key 컬럼을 만들면서 주석에
-- "DB 유니크 제약으로 막는다"고 적었는데 정작 인덱스가 없다.
-- 컬럼만 있고 제약이 없으면 중복이 그대로 들어간다.
--
-- 중복이 나는 경로가 실제로 셋이다.
--   ① 키오스크 재태깅 — 학생이 카드를 두 번 찍는다
--   ② 배치 재실행 — 결석 확정 배치가 실패 후 재시도된다
--   ③ 다중 인스턴스 — 오토스케일링으로 같은 이벤트를 두 서버가 동시에 받는다
-- ③은 애플리케이션 레벨 "먼저 조회하고 없으면 삽입"으로는 못 막는다.
-- 두 트랜잭션이 동시에 조회하면 둘 다 없다고 판단한다.

-- 수기 부여는 idempotency_key가 NULL이라 제약에서 빠진다.
-- NULL끼리는 서로 다르게 취급되므로 일반 UNIQUE로도 되지만,
-- 의도를 드러내고 인덱스 크기를 줄이려 부분 인덱스로 둔다.
CREATE UNIQUE INDEX uq_penalty_point_idempotency
    ON penalty_point (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND NOT is_deleted;

COMMENT ON INDEX uq_penalty_point_idempotency IS
    '자동부여 중복 방지. 키오스크 재태깅·배치 재실행·다중 인스턴스 동시 처리를 막는 최종 방어선';

-- 학생별 상벌점 조회(앱 Daily Report·관리자 내역)가 지배적이다
CREATE INDEX idx_penalty_point_enrollment
    ON penalty_point (enrollment_id, occurred_at DESC)
    WHERE NOT is_deleted;

-- 활성 규칙 조회. 트리거가 발생할 때마다 도는 경로라 인덱스가 없으면 전건 스캔이 된다.
CREATE INDEX idx_penalty_rule_lookup
    ON penalty_rule (academy_id, year, trigger_type)
    WHERE active AND NOT is_deleted;
