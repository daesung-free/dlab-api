-- V20260826_1010: 지점별 교습비·급식업체 연결 시드
--
-- ★ 번호가 _1000 이 아니라 _1010 인 이유 (2026-09-11)
--   db/migration 의 V20260826_1600__staff_enrollment 을 _1000 으로 바꾸면서
--   이 파일과 **같은 버전**이 됐다. 로컬 프로필만 locations 에 db/seed 를 함께
--   넣으므로, 테스트·CI·운영은 멀쩡한데 **로컬만 기동이 막혔다**
--   (Found more than one migration with version 20260826.1000).
--   migration 쪽은 이미 적용된 DB 들이 있어 되돌리기 어려우므로 시드를 비켰다.
--   ⚠️ 마이그레이션 번호를 바꿀 때는 db/seed 와도 겹치는지 확인할 것.
--
-- ★ 여기가 db/migration 이 아니라 db/seed 인 이유
--   지점(academy) 시드가 db/seed 에 있고, 이 값들은 그 지점에 붙는다.
--   마이그레이션에 넣으면 테스트 DB에도 지점이 심어져 **테스트가 스스로 만드는
--   지점과 충돌한다** — 실제로 그렇게 넣었다가 388건이 깨졌다(2026-08-26).
--
-- ★ 왜 필요한가
--   V20260821_1000(교습비 가격)·V20260821_1700(급식업체)의 지점별 시드가
--   academy 를 JOIN 하는데, 마이그레이션 시점엔 academy 가 비어 있어 **전부 0건**이었다.
--   공통 행(N수 660,000+90,000 / 재학생 400,000+90,000)만 들어가 있다.
--   그래서 "공통과 다른 지점"만 여기서 채운다.
--
-- ⚠️ 이 파일은 academy 시드가 먼저 돌아야 의미가 있다. 파일명 순서상 뒤에 온다.

-- ─────────────────────────────────────────────────────────────
-- 1. 목동·분당은 재학생도 N수와 같다 (660,000)
--    그 외 지점 재학생은 공통 행(400,000)으로 떨어진다
-- ─────────────────────────────────────────────────────────────
INSERT INTO tuition_price (academy_id, year, grade_type, seat_type,
                           tuition_fee, study_room_fee, created_by)
SELECT a.id, 2026, g.grade, 'GENERAL', 660000, 90000, 0
FROM academy a
CROSS JOIN (VALUES ('HIGH3'), ('HIGH2')) AS g(grade)
WHERE a.acad_nm IN ('분당', '목동')
  AND NOT EXISTS (
        SELECT 1 FROM tuition_price p
        WHERE p.academy_id = a.id AND p.year = 2026
          AND p.grade_type = g.grade AND p.seat_type = 'GENERAL'
          AND p.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 2. 1인실 — 분당·목동·대구 세 곳뿐이다 (2026-08-26 확인)
--    지점마다 금액이 다르므로 공통 행을 두지 않는다.
--    없는 지점에서 1인실을 고르면 "가격 없음"으로 막히는 것이 맞다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO tuition_price (academy_id, year, grade_type, seat_type,
                           tuition_fee, study_room_fee, created_by)
SELECT a.id, 2026, g.grade, 'SINGLE', p.tuition, p.room, 0
FROM academy a
JOIN (VALUES
        ('분당', 660000, 190000),
        ('목동', 820000, 130000),
        ('대구', 822000, 128000)
    ) AS p(name, tuition, room) ON p.name = a.acad_nm
CROSS JOIN (VALUES ('N_SU'), ('HIGH3'), ('HIGH2')) AS g(grade)
WHERE NOT EXISTS (
        SELECT 1 FROM tuition_price t
        WHERE t.academy_id = a.id AND t.year = 2026
          AND t.grade_type = g.grade AND t.seat_type = 'SINGLE'
          AND t.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 3. 지점별 급식업체·단가
--    대구만 8,000원이고 나머지는 7,700원이다.
--    마감일수는 기본값 3일로 시작한다(MealPolicy.DEFAULT_DEADLINE_DAYS).
-- ─────────────────────────────────────────────────────────────
INSERT INTO meal_policy (academy_id, year, deadline_days, vendor_id, unit_price, created_by)
SELECT a.id, 2026, 3, v.id, m.price, 0
FROM academy a
JOIN (VALUES
        ('분당', '디온푸드', 7700), ('이매', '디온푸드', 7700), ('김포', '디온푸드', 7700),
        ('일산', '디온푸드', 7700), ('광명', '디온푸드', 7700), ('목동', '디온푸드', 7700),
        ('송파', '디온푸드', 7700),
        ('동탄', '니즈푸드', 7700),
        ('부천', '한샘푸드', 7700),
        ('대전', '한끼애',   7700),
        ('대구', '성림푸드', 8000)
    ) AS m(academy_name, vendor_name, price) ON m.academy_name = a.acad_nm
JOIN meal_vendor v ON v.name = m.vendor_name AND v.is_deleted = FALSE
WHERE NOT EXISTS (
        SELECT 1 FROM meal_policy p
        WHERE p.academy_id = a.id AND p.year = 2026 AND p.is_deleted = FALSE);
