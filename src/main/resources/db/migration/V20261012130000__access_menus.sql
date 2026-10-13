-- 출입·출근 관리 화면 메뉴 (긴급출입 개발건, 관리자 웹 8·10·11·12번).
--
-- 계정별 메뉴 노출이 「화면 ↔ 메뉴 코드」 매핑으로 돌아간다. 매핑할 코드가 없으면
-- 조용히 두 가지로 깨진다 — 빈 배열이면 아무에게도 안 보이고, 생략하면 누구에게도
-- 안 감춰진다(노출 설정이 새는 셈이다).
--
-- ★ path_prefix 를 채운다. 그래야 메뉴를 뺀 계정이 **주소를 직접 쳐도 안 열린다**
--   (요구사항 3-2-1 마지막 문장). 역할(@PreAuthorize)로만 막으면 같은 역할 안에서
--   메뉴를 빼도 주소로는 열린다.

INSERT INTO menu (code, name, parent_code, path_prefix, sort_order, action_only)
VALUES
    ('door-code',        '도어락 번호',    NULL, '/api/v1/admin/door-codes',        80, FALSE),
    ('emergency-access', '긴급 출입 내역', NULL, '/api/v1/admin/emergency-access',  81, FALSE),
    -- 07:05 검사 내역과 출근 기록 수정이 같은 경로 아래 있다.
    -- 「화면이 보이나」는 이 메뉴가 정하고, 「고칠 수 있나」는 STAFF_ATTENDANCE_EDIT 가 정한다
    ('staff-attendance', '직원 출근',      NULL, '/api/v1/admin/staff-attendance',  82, FALSE)
ON CONFLICT (code) DO NOTHING;
