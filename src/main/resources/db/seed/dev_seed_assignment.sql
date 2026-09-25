-- 로컬 개발용 시드 ② — 반·좌석·사물함·장학. **운영 금지.**
--
-- dev_seed.sql(계정·학생) 다음에 돌린다. 그쪽 학생을 참조한다.
--
-- ★ 왜 필요한가
--   반 배정·배정 관리·교무업무 명단 세 화면이 이 데이터 없이는 **빈 화면**이다.
--   프론트가 각자 API로 만들어 쓰면 사람마다 데이터가 달라져 화면 확인이 안 맞는다.
--
-- ★ 일부러 남겨두는 것들
--   · **미배정 학생을 남긴다** — 반 배정 화면의 본체가 "반 없는 학생 목록"이다.
--     전원을 배정해버리면 그 화면을 확인할 수 없다.
--   · **빈 좌석·빈 사물함을 남긴다** — 배정 UI 를 눌러볼 수 있어야 한다.
--
-- 멱등이다. 대상 지점은 분당(academy_id = (SELECT id FROM academy WHERE acad_cd = '31'))이고 dev_seed.sql 과 같다.

BEGIN;

-- ── 담임 교사 ─────────────────────────────────────────────
-- 반에 담임이 없으면 화면이 전부 '미지정'으로 나온다.
INSERT INTO teacher (academy_id, name, phone, email, hired_date)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), v.name, v.phone, v.email, DATE '2026-01-02'
FROM (VALUES
    ('김담임', '010-1000-0001', 'teacher1@dlab.local'),
    ('이담임', '010-1000-0002', 'teacher2@dlab.local'),
    ('박담임', '010-1000-0003', 'teacher3@dlab.local')
) AS v(name, phone, email)
WHERE NOT EXISTS (SELECT 1 FROM teacher t WHERE t.email = v.email);

-- ── 반 4개 ────────────────────────────────────────────────
-- ★ 4번 반은 담임을 비워 둔다 — '미지정' 표시를 화면에서 확인할 수 있어야 한다.
INSERT INTO class_master (academy_id, year, name, class_type, capacity, homeroom_teacher_id)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), 2026, v.name, 'FIXED', v.capacity,
       (SELECT t.id FROM teacher t WHERE t.email = v.teacher_email)
FROM (VALUES
    ('N수 1반', 14::smallint, 'teacher1@dlab.local'),
    ('N수 2반', 14::smallint, 'teacher2@dlab.local'),
    ('고3 1반', 12::smallint, 'teacher3@dlab.local'),
    ('고3 2반', 12::smallint, NULL)
) AS v(name, capacity, teacher_email)
WHERE NOT EXISTS (
    SELECT 1 FROM class_master c
    WHERE c.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND c.year = 2026 AND c.name = v.name AND c.is_deleted = FALSE);

-- ── 반 배정 ───────────────────────────────────────────────
-- ★ 분당 학생 20명 중 12명만 배정한다. 나머지 8명은 미배정으로 남는다.
--   반별 인원을 다르게 둬서 충원율이 100%가 아닌 화면을 볼 수 있게 한다.
WITH target AS (
    SELECT e.id, row_number() OVER (ORDER BY e.student_no) AS rn
    FROM student_enrollment e
    WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.is_current = TRUE AND e.is_deleted = FALSE
),
mapped AS (
    SELECT t.id AS enrollment_id,
           (SELECT c.id FROM class_master c
             WHERE c.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND c.year = 2026 AND c.is_deleted = FALSE
               AND c.name = CASE
                     WHEN t.rn <= 5  THEN 'N수 1반'
                     WHEN t.rn <= 9  THEN 'N수 2반'
                     ELSE                 '고3 1반'
                   END) AS class_id
    FROM target t
    WHERE t.rn <= 12
)
INSERT INTO class_assignment (academy_id, enrollment_id, class_id, class_type, assigned_at, is_active)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), m.enrollment_id, m.class_id, 'FIXED', now(), TRUE
FROM mapped m
WHERE m.class_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM class_assignment a
    WHERE a.enrollment_id = m.enrollment_id AND a.is_active = TRUE AND a.is_deleted = FALSE);

-- ── 사물함 12칸 ───────────────────────────────────────────
-- 6칸만 배정하고 나머지는 비워 둔다.
INSERT INTO locker_master (academy_id, locker_no)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), v.no
FROM (VALUES ('A-01'),('A-02'),('A-03'),('A-04'),('A-05'),('A-06'),
             ('B-01'),('B-02'),('B-03'),('B-04'),('B-05'),('B-06')) AS v(no)
WHERE NOT EXISTS (
    SELECT 1 FROM locker_master l WHERE l.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND l.locker_no = v.no);

WITH free_locker AS (
    SELECT id, row_number() OVER (ORDER BY locker_no) AS rn
    FROM locker_master
    WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND assigned_enrollment_id IS NULL AND is_deleted = FALSE
),
target AS (
    SELECT id, row_number() OVER (ORDER BY student_no) AS rn
    FROM student_enrollment
    WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND year = 2026 AND is_current = TRUE AND is_deleted = FALSE
      AND id NOT IN (SELECT assigned_enrollment_id FROM locker_master
                      WHERE assigned_enrollment_id IS NOT NULL)
),
pair AS (
    -- ★ 이미 배정된 수를 빼고 채운다. 안 그러면 돌릴 때마다 6칸씩 더 배정돼
    --   결국 전부 차서 "빈 사물함"을 화면에서 볼 수 없다
    SELECT f.id AS locker_id, t.id AS enrollment_id, f.rn
    FROM free_locker f JOIN target t ON t.rn = f.rn
    WHERE f.rn <= GREATEST(0, 6 - (SELECT count(*) FROM locker_master
                                    WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND assigned_enrollment_id IS NOT NULL))
)
UPDATE locker_master l
   SET assigned_enrollment_id = p.enrollment_id
  FROM pair p
 WHERE l.id = p.locker_id;

-- ── 독서실 구역·좌석 ──────────────────────────────────────
-- ★ 좌석 마스터를 만드는 관리자 API 가 아직 없다(API_GAPS 4-5).
--   좌석을 쓰는 화면이 3개(배정 관리·좌석배치표·좌석 이탈)라 시드로라도 넣어 둔다.
--   area_cd 는 키오스크 계약이 쓰는 값이라 DSA 표기(A/B)를 따른다.
-- 관(building)은 마이그레이션이 지점마다 'MAIN' 을 만들어 둔다. 여기 구역은 전부 본관이다.
INSERT INTO study_area (academy_id, building_id, area_cd, kiosk_area_cd, area_nm, sort_order, active)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'),
       (SELECT b.id FROM building b
         WHERE b.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND b.code = 'MAIN'),
       v.cd, v.cd, v.nm, v.ord, TRUE
FROM (VALUES ('A', 'A구역', 1::smallint), ('B', 'B구역', 2::smallint)) AS v(cd, nm, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM study_area s WHERE s.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND s.area_cd = v.cd);

-- 구역당 20석(4행 × 5열). 좌표는 배치도 그리기용이다.
-- 본관이라 kiosk_seat_cd = seat_cd 다(별관이면 관 offset 이 더해진다).
INSERT INTO seat_master (academy_id, study_area_id, seat_cd, kiosk_seat_cd, seat_nm, x_pos, y_pos, usable)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), s.id,
       s.area_cd || lpad(g.n::text, 2, '0'),
       s.area_cd || lpad(g.n::text, 2, '0'),
       s.area_cd || '-' || lpad(g.n::text, 2, '0'),
       ((g.n - 1) % 5) + 1,
       ((g.n - 1) / 5) + 1,
       TRUE
FROM study_area s
CROSS JOIN generate_series(1, 20) AS g(n)
WHERE s.academy_id = (SELECT id FROM academy WHERE acad_cd = '31')
  AND NOT EXISTS (
    SELECT 1 FROM seat_master m
    WHERE m.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND m.seat_cd = s.area_cd || lpad(g.n::text, 2, '0'));

-- 좌석 배정 8석. 나머지는 비워 둔다.
WITH pair AS (
    SELECT m.id AS seat_id, e.id AS enrollment_id
    FROM (SELECT id, row_number() OVER (ORDER BY seat_cd) AS rn
            FROM seat_master WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31')) m
    JOIN (SELECT id, row_number() OVER (ORDER BY student_no) AS rn
            FROM student_enrollment
           WHERE academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND year = 2026 AND is_current = TRUE) e ON e.rn = m.rn
    WHERE m.rn <= 8
)
INSERT INTO seat_assignment (academy_id, seat_id, enrollment_id, assigned_at)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), p.seat_id, p.enrollment_id, now()
FROM pair p
WHERE NOT EXISTS (
    SELECT 1 FROM seat_assignment a
    WHERE a.enrollment_id = p.enrollment_id AND a.released_at IS NULL AND a.is_deleted = FALSE);

-- ── 주소 ──────────────────────────────────────────────────
-- 수강생 대장의 '주소' 컬럼이 비어 있었다. ⚠️ STAFF 계정에는 마스킹돼 나간다.
UPDATE student s
   SET address = v.addr
  FROM (
    SELECT unique_code, addr FROM (VALUES
        ('S20260001', '경기도 성남시 분당구 판교로 235'),
        ('S20260002', '경기도 성남시 분당구 정자일로 95'),
        ('S20260003', '경기도 용인시 수지구 성복2로 38'),
        ('S20260004', '서울시 강남구 테헤란로 152'),
        ('S20260005', '경기도 성남시 분당구 황새울로 246')
    ) AS t(unique_code, addr)
  ) v
 WHERE s.unique_code = v.unique_code AND s.address IS NULL;

-- 나머지 학생도 비어 보이지 않게 지점 소재지로 채운다.
UPDATE student SET address = '경기도 성남시 분당구'
 WHERE unique_code LIKE 'S2026%' AND address IS NULL;

-- ── 장학 ──────────────────────────────────────────────────
-- 교무업무 명단의 '장학생' 탭용. 학생 20명 중 5명만 준다.
WITH target AS (
    SELECT e.id, row_number() OVER (ORDER BY e.student_no) AS rn
    FROM student_enrollment e
    WHERE e.academy_id = (SELECT id FROM academy WHERE acad_cd = '31') AND e.year = 2026 AND e.is_current = TRUE AND e.is_deleted = FALSE
)
INSERT INTO scholarship (academy_id, enrollment_id, scholarship_type, discount_rate)
SELECT (SELECT id FROM academy WHERE acad_cd = '31'), t.id,
       CASE t.rn WHEN 1 THEN 'CSAT_100' WHEN 2 THEN 'CSAT_50'
                 WHEN 3 THEN 'KICE_50'  WHEN 4 THEN 'KICE_30'
                 ELSE 'NASIN_50' END,
       CASE t.rn WHEN 1 THEN 100.00 WHEN 2 THEN 50.00
                 WHEN 3 THEN 50.00  WHEN 4 THEN 30.00
                 ELSE 50.00 END
FROM target t
WHERE t.rn <= 5
  AND NOT EXISTS (
    SELECT 1 FROM scholarship sc WHERE sc.enrollment_id = t.id AND sc.is_deleted = FALSE);

COMMIT;
