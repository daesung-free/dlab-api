-- 교시 마스터 개발용 시드 (자동 적용 아님 — 수동 실행)
--
-- ★ 마이그레이션에 넣지 않은 이유
--   교시는 스키마가 아니라 운영 데이터다. 지점마다 다를 수 있고 연도마다 바뀐다.
--   마이그레이션에 박으면 2027년에 또 마이그레이션을 만들어야 하고,
--   나중에 지점을 추가하면 그 지점만 교시가 비어 있게 된다.
--   정식 관리 경로는 Phase 3(학습계획 관리 화면에서 20/30분 단위 편집)이다.
--
-- ★ 지금 이게 필요한 이유
--   출결 판정(code 113 시간표 없음 / 122 학습시간 외)이 이 데이터에 의존해서,
--   비어 있으면 태깅 개발·검수를 아예 못 한다.
--
-- 실행:
--   psql -U dlab -d dlab_local -f src/main/resources/db/seed/period_master_dev.sql
--
-- 근거: 디자인 시안 plan-1~3, 07_admin_plan (시간까지 확정됨)

-- 평일 — 0교시 07:40 ~ 야3 23:20, 14블록
INSERT INTO period_master (academy_id, year, day_type, period_no, name, period_type, planable, start_time, end_time)
SELECT a.id, EXTRACT(YEAR FROM CURRENT_DATE)::smallint, 'WEEKDAY', v.no, v.nm, v.tp, v.pl, v.st::time, v.ed::time
FROM academy a,
     (VALUES
          (0,  '0교시',  'CLASS',      TRUE,  '07:40', '08:10'),
          (1,  '1교시',  'CLASS',      TRUE,  '08:15', '09:30'),
          (2,  '2교시',  'CLASS',      TRUE,  '09:40', '10:55'),
          (3,  '3교시',  'CLASS',      TRUE,  '11:05', '12:20'),
          (4,  '점심',   'MEAL',       TRUE,  '12:20', '13:20'),
          (5,  '4교시',  'CLASS',      TRUE,  '13:30', '14:45'),
          (6,  '5교시',  'CLASS',      TRUE,  '14:55', '16:10'),
          (7,  '6교시',  'CLASS',      TRUE,  '16:20', '17:35'),
          (8,  '저녁',   'MEAL',       TRUE,  '17:35', '18:40'),
          (9,  '종례',   'ETC',        FALSE, '18:40', '19:00'),
          (10, '야1',    'SELF_STUDY', TRUE,  '19:00', '20:20'),
          (11, '간식',   'BREAK',      FALSE, '20:20', '20:40'),
          (12, '야2',    'SELF_STUDY', TRUE,  '20:40', '22:00'),
          (13, '야3',    'SELF_STUDY', TRUE,  '22:00', '23:20')
     ) AS v(no, nm, tp, pl, st, ed)
WHERE NOT EXISTS (
    SELECT 1 FROM period_master p
    WHERE p.academy_id = a.id
      AND p.year = EXTRACT(YEAR FROM CURRENT_DATE)::smallint
      AND p.day_type = 'WEEKDAY'
      AND p.period_no = v.no
);

-- 토요일 — 시안 plan-3 기준. 0교시·2교시가 없고 점심 이후 구성이 평일과 같다.
INSERT INTO period_master (academy_id, year, day_type, period_no, name, period_type, planable, start_time, end_time)
SELECT a.id, EXTRACT(YEAR FROM CURRENT_DATE)::smallint, 'SATURDAY', v.no, v.nm, v.tp, v.pl, v.st::time, v.ed::time
FROM academy a,
     (VALUES
          (1,  '1교시',  'CLASS',      TRUE,  '08:15', '09:30'),
          (3,  '3교시',  'CLASS',      TRUE,  '11:05', '12:20'),
          (4,  '점심',   'MEAL',       TRUE,  '12:20', '13:20'),
          (5,  '4교시',  'CLASS',      TRUE,  '13:30', '14:45'),
          (6,  '5교시',  'CLASS',      TRUE,  '14:55', '16:10'),
          (7,  '6교시',  'CLASS',      TRUE,  '16:20', '17:35'),
          (8,  '저녁',   'MEAL',       TRUE,  '17:35', '18:40'),
          (10, '야1',    'SELF_STUDY', TRUE,  '19:00', '20:20'),
          (11, '간식',   'BREAK',      FALSE, '20:20', '20:40'),
          (12, '야2',    'SELF_STUDY', TRUE,  '20:40', '22:00'),
          (13, '야3',    'SELF_STUDY', TRUE,  '22:00', '23:20')
     ) AS v(no, nm, tp, pl, st, ed)
WHERE NOT EXISTS (
    SELECT 1 FROM period_master p
    WHERE p.academy_id = a.id
      AND p.year = EXTRACT(YEAR FROM CURRENT_DATE)::smallint
      AND p.day_type = 'SATURDAY'
      AND p.period_no = v.no
);

-- 일요일은 시안에 없다. 운영 확인 전까지 비워둔다 —
-- 임의로 넣으면 없는 시간표로 출결이 판정된다.
