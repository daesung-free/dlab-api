-- 와이파이 제어 단위가 Voucher 로 확정됐다 (2026-10-06 자이엘 회신).
-- 그동안 제어 단위가 미정(E-1)이라 대상을 문자열 하나로 열어 두고 로그만 찍었다.
--
-- ★ 배정한 코드를 신청 건에 남겨야 한다. 차단이 「logout-user + delete」 두 호출이고
--   둘 다 그 코드를 받기 때문에, 코드를 모르면 열어 준 와이파이를 닫을 수가 없다.

ALTER TABLE firewall_request
    -- 배정된 Voucher 코드. 학생에게 전달되는 값이기도 하다
    ADD COLUMN voucher_code    VARCHAR(32),
    -- ★ 차단 실패 횟수. 가장 위험한 상태가 「차단 실패 → 와이파이가 열린 채 잔존」이고,
    --   벤더가 재시도를 제공하지 않아 우리가 세어야 한다
    ADD COLUMN block_attempts  SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN block_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN firewall_request.block_failed_at IS
    '마지막 차단 실패 시각. 값이 있으면 아직 열려 있을 수 있어 관리자가 확인해야 한다';

-- 지점별 Nebula 사이트. 장비 ID(nebula_device_id)와 다른 축이다 —
-- Voucher API 는 사이트 단위로 호출한다
ALTER TABLE branch_config
    ADD COLUMN nebula_site_id VARCHAR(64);

COMMENT ON COLUMN branch_config.nebula_site_id IS
    'Nebula 사이트 ID. 지점마다 다르고 Voucher 호출 경로에 들어간다';
