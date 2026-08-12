-- V20260812_2000: 상벌점 항목·규칙 관리 (F-4.1-3, I-5)
--
-- 클라이언트가 "상벌점 점수 생성 페이지에서 직접 입력"으로 답을 줬다.
-- 항목·규칙을 관리자가 만들 수 있어야 하는데, 지금은 조회만 있고 생성이 없어
-- DB에 직접 넣어야 했다.

-- ─────────────────────────────────────────────────────────────
-- 1. 항목 유니크를 부분 인덱스로
--
-- ★ 삭제한 항목 이름을 다시 못 쓴다. soft delete한 행이 유니크를 계속 잡고 있어
--   "지각"을 지웠다가 다시 만들면 실패한다. 살아 있는 행만 유일하면 된다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE penalty_item DROP CONSTRAINT IF EXISTS uq_penalty_item;

CREATE UNIQUE INDEX uq_penalty_item
    ON penalty_item (academy_id, year, item_name) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 2. 벌점 부호를 구분에 맞춰 정리
--
-- ★ 통계·합계가 부호로 상점과 벌점을 가른다. 벌점이 양수로 저장되면 그 학생의
--   벌점이 상점으로 집계되는데, 화면에는 "벌점 5점"으로 정상으로 보여 아무도
--   눈치채지 못한다. 앞으로는 엔티티가 저장 시 부호를 맞추고, 기존 행은 여기서 고친다.
--
-- penalty_point는 건드리지 않는다 — 부여 시점의 사실이라 소급해서 바꾸면
-- "그때 몇 점을 받았나"가 달라진다.
-- ─────────────────────────────────────────────────────────────
UPDATE penalty_item
   SET point_value = -ABS(point_value)
 WHERE category = 'DEMERIT' AND point_value > 0;

UPDATE penalty_item
   SET point_value = ABS(point_value)
 WHERE category = 'MERIT' AND point_value < 0;

-- ─────────────────────────────────────────────────────────────
-- 3. 규칙 중복 방지
--
-- ★ 같은 트리거·조건·항목 규칙이 두 개면 한 번의 지각에 벌점이 두 번 부여된다.
--   멱등키가 (학생:일자:규칙)이라 규칙이 다르면 둘 다 통과하기 때문이다.
--
--   다른 항목을 연결한 규칙은 허용한다 — "지각이면 벌점 + 별도 항목"이 실제로
--   있을 수 있고, 그건 의도된 조합이다.
-- ─────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_penalty_rule
    ON penalty_rule (academy_id, year, trigger_type, trigger_condition, penalty_item_id)
    WHERE is_deleted = FALSE;

COMMENT ON INDEX uq_penalty_rule IS
    '같은 트리거·조건·항목 규칙 중복 방지. 중복이면 한 번의 지각에 두 번 부여된다';
