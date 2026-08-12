-- V20260812_1700: 방화벽 해제 상태 (F-4.11-10)
--
-- 위반(firewall_violation)·제재(firewall_restriction) 테이블은 V1에 이미 있다.
-- 여기서는 "지금 해제중인가"를 판별할 상태 컬럼만 더한다.
--
-- ★ 승인 상태와 해제 상태는 다르다.
--   approval_request.status는 "학부모가 승인했나"이고, 승인됐어도 해제 시간이
--   지나면 와이파이는 닫혀야 한다. 시각(unlock_end_at)만으로 판별하면
--   "만료 처리를 했는가"(= Nebula에 차단을 보냈는가)와 구분되지 않는다 —
--   그 둘을 구분해야 스케줄러가 무엇을 아직 안 했는지 알 수 있다.

ALTER TABLE firewall_request
    ADD COLUMN unlock_status VARCHAR(20) NOT NULL DEFAULT 'WAITING';

ALTER TABLE firewall_request
    ADD CONSTRAINT ck_firewall_unlock_status
    CHECK (unlock_status IN ('WAITING', 'ACTIVE', 'EXPIRED', 'CANCELED'));

-- 이미 있는 행을 시각 기준으로 메운다. 개발 데이터뿐이지만
-- 전부 WAITING으로 두면 지난 건이 "아직 시작 안 함"으로 보인다
UPDATE firewall_request
   SET unlock_status = CASE
        WHEN unlock_start_at IS NULL              THEN 'WAITING'
        WHEN unlock_end_at   > now()              THEN 'ACTIVE'
        ELSE 'EXPIRED'
   END;

-- "현재 해제중" 목록 — 관리자 화면이 가장 자주 여는 조회다
CREATE INDEX idx_firewall_request_active
    ON firewall_request (academy_id, unlock_status, unlock_end_at)
    WHERE is_deleted = FALSE;

-- 만료 스케줄러가 훑는 경로
CREATE INDEX idx_firewall_request_expiry
    ON firewall_request (unlock_status, unlock_end_at)
    WHERE is_deleted = FALSE;

COMMENT ON COLUMN firewall_request.unlock_status IS
    'WAITING=승인 전·미개시 / ACTIVE=해제중 / EXPIRED=만료 차단됨 / CANCELED=취소. 승인 상태와 별개다';
