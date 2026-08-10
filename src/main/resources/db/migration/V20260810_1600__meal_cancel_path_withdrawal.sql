-- V20260810_1600: 급식 취소 경로에 WITHDRAWAL 추가
--
-- 퇴원·제적·수료 시 남은 급식 신청을 자동 취소한다. 이 경로를 CLOSURE(중단일)와
-- 합치지 않은 이유는 ★ 환불 근거가 다르기 때문이다 — 중단일은 학원 사정이라 전액
-- 환불이고, 퇴원은 학원법 반환기준(I-26 일할계산)을 탄다. 한 값으로 묶으면
-- payment가 붙을 때 둘을 다시 갈라낼 방법이 없다.

ALTER TABLE meal_order_item DROP CONSTRAINT IF EXISTS meal_order_item_cancel_path_check;

ALTER TABLE meal_order_item
    ADD CONSTRAINT meal_order_item_cancel_path_check
        CHECK (cancel_path IN ('APP', 'DESK', 'CLOSURE', 'WITHDRAWAL'));

COMMENT ON COLUMN meal_order_item.cancel_path IS
    'APP(마감 전 학생) / DESK(관리자 즉시) / CLOSURE(중단일 등록) / WITHDRAWAL(퇴원·제적·수료 정리)';
