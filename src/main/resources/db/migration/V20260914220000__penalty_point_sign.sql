-- 벌점 항목이 양수로 저장된 행을 바로잡는다.
--
-- ★ 부호가 상점·벌점을 가르는 축이다. 벌점이 양수로 들어가면 합계에서 상점으로 집계되고,
--   화면에는 "벌점 5점" 으로 정상으로 보인다 — 숫자만 보면 알 수 없다.
--
-- ★ 엔티티는 이미 저장 시 부호를 맞추는데(PenaltyItem.signed), 그 코드가 생기기 전에
--   들어간 행과 시드로 직접 넣은 행이 양수로 남아 있었다. 그래서 조회 경로 둘이
--   다르게 보였다 — 한쪽은 읽을 때 부호를 고쳐 내리고, 다른 쪽은 저장된 값을 그대로 줬다.
UPDATE penalty_item
   SET point_value = -ABS(point_value)
 WHERE category = 'DEMERIT'
   AND point_value > 0;

-- 부여 이력도 같은 이유로 맞춘다.
--
-- ⚠️ 이력을 고치는 것은 원칙적으로 피해야 하지만, 이건 "그때 몇 점이었나" 가 아니라
--    같은 점수를 반대로 집계하던 것을 되돌리는 일이다. 놔두면 그 학생의 벌점이 상점으로
--    잡혀 제적 기준(40점) 판정이 어긋난다. 크기는 건드리지 않는다.
UPDATE penalty_point p
   SET points = -ABS(p.points)
  FROM penalty_item i
 WHERE i.id = p.penalty_item_id
   AND i.category = 'DEMERIT'
   AND p.points > 0;
