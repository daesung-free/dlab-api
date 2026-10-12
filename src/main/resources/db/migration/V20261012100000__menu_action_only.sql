-- 「화면 없는 권한」을 메뉴 카탈로그에 담는다 (긴급출입 개발건, 2026-10-08 디랩 결정).
--
-- 요구사항이 권한 부여를 「계정 등록 화면의 메뉴별 체크박스」로 정했고, 그 체크박스 목록이
-- 이 카탈로그에서 그려진다. 그런데 새로 들어오는 두 항목은 **누를 화면이 없는 권한**이다.
--
-- ★ 그냥 넣으면 사이드바와 「자주 쓰는 메뉴」에 후보로 올라온다 — 눌러도 갈 데가 없는
--   항목이 사이드바에 생기면 고장으로 읽힌다. 그래서 축을 하나 둔다.
--   path_prefix 가 비어 있는 것과는 다른 뜻이다(그건 "서버가 막지 못한다"는 뜻이고,
--   화면이 있는 메뉴도 그럴 수 있다).

ALTER TABLE menu
    ADD COLUMN action_only BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN menu.action_only IS
    '화면 없는 권한. 체크박스 목록에는 나오고 사이드바·자주 쓰는 메뉴에서는 빠진다';

-- 긴급 출입 · 오전 출근 확인 (요구사항 3-2)
INSERT INTO menu (code, name, parent_code, path_prefix, sort_order, action_only)
VALUES
    -- 이 권한을 가진 계정이 문자를 받는다. 발송 대상을 역할로 고정하지 않는다는 결정이다
    ('EMERGENCY_ACCESS_ALERT', '긴급 출입 · 출근 확인 알림 수신', NULL, NULL, 90, TRUE),
    -- 이 권한을 가진 계정만 직원 출근 기록을 고칠 수 있다.
    -- ⚠️ path_prefix 는 수정 API 가 생길 때 채운다 — 지금 채우면 없는 경로를 막는 설정이 된다
    ('STAFF_ATTENDANCE_EDIT', '직원 출근 기록 수정', NULL, NULL, 91, TRUE)
ON CONFLICT (code) DO NOTHING;
