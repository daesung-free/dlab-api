-- V20260807_1740: 실제 교시표 시드 (2026-08-07 수령)
--
-- ★ 이게 없으면 모든 태깅이 code 113(시간표 없음)으로 거부된다.
--   교시 마스터는 출결 판정·순공시간·학습계획이 함께 보는 유일한 출처다.
--
-- ★ 쉬는시간 행을 넣지 않는다.
--   교시 사이 공백(10:00~10:20 등)이 곧 쉬는시간이고, 순공시간은 학습 교시와의
--   교집합으로 구하므로 공백은 자동으로 빠진다. 행을 만들면 관리 대상만 늘어난다.
--
-- ★ 주말·공휴일도 교시가 그대로 있다 — 전부 자율선택자습이고 9교시만 미운영이다.
--   mandatory=FALSE라서 지각 판정이 돌지 않고, 하원 경계도 생기지 않는다
--   (재태깅은 113으로 되물어 학생이 하원·외출을 고른다).
--
-- ★ 전 지점 공통으로 넣는다. 지점마다 다르면 관리 화면에서 고친다.

INSERT INTO period_master
    (academy_id, year, period_no, name, day_type, period_type,
     start_time, end_time, planable, mandatory)
SELECT a.id, 2026, p.no::smallint, p.nm, d.day_type, p.tp,
       p.st::time, p.et::time, TRUE, p.mandatory AND d.day_type = 'WEEKDAY'
FROM academy a
CROSS JOIN (VALUES ('WEEKDAY'), ('SATURDAY'), ('SUNDAY')) AS d(day_type)
CROSS JOIN (VALUES
    ( 0, '오픈시간', 'SELF_STUDY', '07:00', '08:00', FALSE),
    ( 1, '1교시',    'SELF_STUDY', '08:00', '10:00', TRUE ),
    ( 2, '2교시',    'SELF_STUDY', '10:20', '12:10', TRUE ),
    ( 3, '점심식사',  'MEAL',       '12:10', '13:10', FALSE),
    ( 4, '3교시',    'SELF_STUDY', '13:10', '14:20', TRUE ),
    ( 5, '4교시',    'SELF_STUDY', '14:40', '16:10', TRUE ),
    ( 6, '5교시',    'SELF_STUDY', '16:30', '18:00', TRUE ),
    ( 7, '저녁식사',  'MEAL',       '18:00', '19:00', FALSE),
    ( 8, '6교시',    'SELF_STUDY', '19:00', '20:20', TRUE ),
    ( 9, '7교시',    'SELF_STUDY', '20:40', '21:50', TRUE ),
    (10, '8교시',    'SELF_STUDY', '22:00', '22:50', FALSE),
    (11, '9교시',    'SELF_STUDY', '23:00', '23:50', FALSE)
) AS p(no, nm, tp, st, et, mandatory)
-- 9교시는 주말·공휴일 미운영
WHERE NOT (p.no = 11 AND d.day_type <> 'WEEKDAY')
  AND NOT EXISTS (
      SELECT 1 FROM period_master pm
      WHERE pm.academy_id = a.id AND pm.year = 2026
        AND pm.day_type = d.day_type AND pm.period_no = p.no::smallint);
