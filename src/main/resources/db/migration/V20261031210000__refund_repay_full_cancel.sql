-- ★ 파일명이 14자리인 이유는 V20261031150000 머리말과 같다.

-- 환불 ★ 재결제 후 전체취소 (디랩 1029 7장)
--
-- 디랩 원문: *「부분취소, ★ 재결제 후 전체취소 모두 필요할 것 같습니다.
--            ★ 지점이나 상황에 따라 골라서 사용할 수 있도록 부탁드립니다」*
--
-- ★★ 순서가 전부다.
--
--    ① 재결제 금액으로 ★ 결제 요청을 학부모에게 보낸다
--       (★ 카드 정보를 보관하지 않으므로 — 빌링키 없음 — ★ 서버가 혼자 결제할 수 없다)
--    ② 재결제가 ★ 완료된 것을 확인한 뒤 기결제를 전체취소한다
--
-- ⚠️⚠️ ★ 전체취소를 먼저 하지 말 것 — ★ 학부모가 재결제를 안 하면 ★ 받을 돈이 사라진다.
--    그래서 재결제 대기 중에는 ★ WAITING_REPAYMENT 로 두고 화면에 「재결제 대기」로
--    보여 준다.
--
-- ⚠️ ★ 가상계좌로 받은 기결제 건은 이 방식이 안 된다 — 계좌 이체 환불이다(10/12 회신).
--    화면에서 그 선택지를 끄고 안내 문구로 대신한다.
--
-- ★ 금액 계산(구간·일할·정상가 차감)은 그대로다 — ★ 바뀌는 것은 ★ 돌려주는 방법뿐이다.

-- ⚠️⚠️ ★ 컬럼을 먼저 넓힌다 — 'REPAY_AND_FULL_CANCEL' 이 ★ 21자이고
--    method 가 ★ varchar(20) 이다. ★ 넓히지 않으면 저장할 때
--    「value too long for type character varying(20)」 으로 깨진다.
--    ★ 테스트가 이것을 잡았다.
ALTER TABLE refund
    ALTER COLUMN method TYPE VARCHAR(30),
    ALTER COLUMN status TYPE VARCHAR(30);

ALTER TABLE refund
    DROP CONSTRAINT IF EXISTS refund_method_check;

ALTER TABLE refund
    ADD CONSTRAINT refund_method_check
        CHECK (method IN ('CARD_CANCEL', 'BANK_TRANSFER', 'NONE',
                          'REPAY_AND_FULL_CANCEL'));

ALTER TABLE refund
    DROP CONSTRAINT IF EXISTS refund_status_check;

ALTER TABLE refund
    ADD CONSTRAINT refund_status_check
        CHECK (status IN ('PENDING', 'DONE', 'FAILED', 'ADDITIONAL_PAYMENT',
                          'WAITING_REPAYMENT'));

-- ★ 재결제 요청을 어디로 보냈는지 · 언제 확인했는지.
--
-- ⚠️ 없으면 ★ 「요청은 보냈나」와 「아직 안 보냈나」가 구분되지 않아
--    ★ 전체취소를 걸어도 되는지 알 수 없다.
ALTER TABLE refund
    ADD COLUMN IF NOT EXISTS repay_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS repay_amount INTEGER,
    ADD COLUMN IF NOT EXISTS repay_confirmed_at TIMESTAMPTZ;

COMMENT ON COLUMN refund.repay_amount IS
    '재결제로 다시 받아야 하는 금액. 기결제 전체취소 전에 이 금액이 들어와야 한다.';

COMMENT ON COLUMN refund.repay_confirmed_at IS
    '재결제가 확인된 시각. 이 값이 있어야 기결제 전체취소를 걸 수 있다 — '
    '먼저 취소하면 학부모가 재결제를 안 할 때 받을 돈이 사라진다.';

-- ⚠️ 활성 환불 유니크 인덱스에 ★ WAITING_REPAYMENT 를 더한다.
--
--    ★ 안 더하면 ★ 재결제 대기 중인 청구에 ★ 환불을 또 걸 수 있다 —
--    그러면 ★ 전체취소가 두 번 걸린다.
DROP INDEX IF EXISTS uq_refund_billing_active;

CREATE UNIQUE INDEX uq_refund_billing_active
    ON refund (billing_id)
 WHERE is_deleted = false
   AND status IN ('PENDING', 'DONE', 'ADDITIONAL_PAYMENT', 'WAITING_REPAYMENT');
