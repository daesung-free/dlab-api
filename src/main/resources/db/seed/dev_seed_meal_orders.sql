-- 급식 신청 시드 (화면 확인용). 멱등이라 여러 번 돌려도 된다.
--
-- ★ 신청은 **앱에서만** 만들어져서 관리자 화면만으로는 표를 그려볼 수가 없다.
--   일자별 현황이 실제로 어떻게 보이는지 확인하려면 데이터가 먼저 있어야 한다.
--
-- 분당 학생 중 8명에게 이번 달 신청을 넣는다. 섞는 것 셋:
--   ① 점심만 / 저녁만 / 둘 다  — 화면이 끼니를 갈라 세는지 보려면 섞여야 한다
--   ② 취소분(`canceled_at`)     — 취소가 명단에서 빠지는지 확인용
--   ③ 취소 경로 APP·DESK        — 앱은 D-3 제한, 데스크는 무제한이라 둘이 다르다

-- 이번 달 1일
WITH month AS (SELECT date_trunc('month', CURRENT_DATE)::date AS m),
     target AS (
         SELECT e.id AS enrollment_id, e.academy_id,
                row_number() OVER (ORDER BY e.id) AS rn
         FROM student_enrollment e
         JOIN academy a ON a.id = e.academy_id
         WHERE a.acad_cd = '31' AND NOT e.is_deleted AND e.is_current
         LIMIT 8
     )
INSERT INTO meal_order (academy_id, enrollment_id, target_month, status, created_by)
SELECT t.academy_id, t.enrollment_id, (SELECT m FROM month), 'PAID', 0
FROM target t
WHERE NOT EXISTS (
    SELECT 1 FROM meal_order o
    WHERE o.enrollment_id = t.enrollment_id
      AND o.target_month = (SELECT m FROM month)
      AND NOT o.is_deleted);

-- 끼니. 평일만 넣는다 — 주말은 급식이 없다(`HolidayCalendar.mealAvailableDates`)
--
-- ⚠️ 공휴일은 걸러내지 않는다. 시드라 그 정도면 충분하고, 실제 판정은 `holiday` 테이블이 한다
WITH month AS (SELECT date_trunc('month', CURRENT_DATE)::date AS m),
     ordered AS (
         SELECT o.id, row_number() OVER (ORDER BY o.id) AS rn
         FROM meal_order o
         JOIN academy a ON a.id = o.academy_id
         WHERE a.acad_cd = '31' AND o.target_month = (SELECT m FROM month)
           AND NOT o.is_deleted
     ),
     days AS (
         SELECT d::date AS meal_date
         FROM generate_series((SELECT m FROM month),
                              (SELECT m FROM month) + INTERVAL '13 days', INTERVAL '1 day') d
         WHERE EXTRACT(ISODOW FROM d) < 6
     )
INSERT INTO meal_order_item (order_id, meal_date, meal_type, unit_price,
                             canceled_at, cancel_path, created_by)
SELECT o.id, d.meal_date, t.meal_type, 7700,
       -- 매 7번째 끼니를 취소분으로 둔다. 전부 정상이면 "취소가 빠지는지" 를 못 본다
       CASE WHEN (o.rn + EXTRACT(DAY FROM d.meal_date)::int) % 7 = 0
            THEN now() ELSE NULL END,
       CASE WHEN (o.rn + EXTRACT(DAY FROM d.meal_date)::int) % 7 = 0
            THEN CASE WHEN o.rn % 2 = 0 THEN 'APP' ELSE 'DESK' END ELSE NULL END,
       0
FROM ordered o
CROSS JOIN days d
CROSS JOIN (VALUES ('LUNCH'), ('DINNER')) AS t(meal_type)
-- ① 1~3번 학생은 점심만, 4~5번은 저녁만, 나머지는 둘 다
--
-- ⚠️ **바깥 괄호를 지우지 말 것.** `AND` 가 `OR` 보다 먼저 묶여서, 괄호 없이 쓰면
--    아래 `NOT EXISTS` 가 **마지막 분기에만** 걸린다 — 겉보기엔 멱등인데 두 번째
--    실행에서 앞 두 분기가 통째로 다시 들어간다(실제로 50건이 더 들어갔다).
WHERE ((o.rn <= 3 AND t.meal_type = 'LUNCH')
    OR (o.rn IN (4, 5) AND t.meal_type = 'DINNER')
    OR (o.rn >= 6))
  AND NOT EXISTS (
    SELECT 1 FROM meal_order_item i
    WHERE i.order_id = o.id AND i.meal_date = d.meal_date
      AND i.meal_type = t.meal_type AND NOT i.is_deleted);
