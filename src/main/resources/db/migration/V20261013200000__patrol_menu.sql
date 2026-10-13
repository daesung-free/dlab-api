-- 순찰 메뉴 코드 (2026-10-13 프론트 확인).
--
-- ★★ 순찰 화면이 담임에게 통째로 막혀 있었다. 전용 코드가 없어 프론트가 `seat` 에
--    임시로 매달아 뒀는데, `seat` 는 데스크 업무(WITH_STAFF)라 담임 메뉴에 안 내려간다.
--
-- ⚠️ **`seat` 를 담임에게 주는 것으로 때우지 않는다** — 그러면 독서실 좌석배치·좌석 이탈·
--    배정 관리까지 같이 열린다. 순찰만 열려야 한다.
--
-- 순찰은 사감(담임)이 도는 업무다(`RoleMenuDefaults` 에서 WITH_TEACHER 로 준다).
INSERT INTO menu (code, name, parent_code, path_prefix, sort_order)
SELECT 'patrol', '순찰 관리', 'student', '/api/v1/admin/patrols', 17
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE code = 'patrol');
