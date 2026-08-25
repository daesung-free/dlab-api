-- V20260825_1000: 급식 주문 ↔ 청구 연결 (F-4.5 · F-4.8-1)
--
-- 급식 단가가 들어오면서 "얼마인가"가 생겼다. 이제 월별 주문을 청구로 넘긴다.
--
-- ★ 주문과 청구를 FK로 잇는 이유는 "발행 후 취소" 때문이다.
--   급식은 날짜·끼니 단위로 취소되는데, 청구를 낸 뒤 취소되면
--   **청구액과 실제 이용액이 어긋난다.** 그 차액이 곧 환불 대상인데,
--   연결이 없으면 "이 주문이 어느 청구였는지"를 되짚을 방법이 없다.
--
--   (enrollment, 월, MEAL)로 찾을 수도 있지만, 그건 조건이 셋이라
--   한 군데서 빠뜨리면 조용히 다른 청구를 집는다. FK 하나가 명확하다.

ALTER TABLE meal_order ADD COLUMN billing_id BIGINT REFERENCES billing (id);

-- 한 주문은 청구 한 건에만 붙는다. 두 번 발행되면 학생이 두 번 낸다
CREATE UNIQUE INDEX uq_meal_order_billing
    ON meal_order (billing_id) WHERE billing_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN meal_order.billing_id IS
    '발행된 청구. 발행 후 취소된 끼니의 환불 대상 금액을 이 청구액과 대조해 낸다';
