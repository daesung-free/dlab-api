-- 사이트코드 채널에 현금영수증을 더한다. 발급 주체가 사업자라 급식비 영수증은
-- 업체 코드로 나가야 한다 — 학원 코드로 발급하면 학원 매출로 잡힌다.
ALTER TABLE pg_site DROP CONSTRAINT IF EXISTS pg_site_channel_check;
ALTER TABLE pg_site
    ADD CONSTRAINT pg_site_channel_check
    CHECK (channel IN ('BUYLINK', 'TERMINAL', 'VBANK', 'CASH_RECEIPT'));

-- 현금영수증.
--
-- ★ 왜 별도 표인가 — 발급 단위가 수납 단위와 다르다.
--   한 수납에 영수증이 없을 수도 있고(카드 결제는 발급 대상이 아니다), 발급했다가
--   취소하고 다시 발급하는 경우도 있다. payment_transaction 에 칸을 더하면
--   "발급 안 함" 과 "취소됨" 이 구분되지 않는다.
--
-- ★ 발급 대상은 현금성 거래다 — 계좌이체·가상계좌·무통장입금. 카드 결제는 카드사가
--   이미 소득공제를 처리하므로 발급하면 이중이 된다.
CREATE TABLE cash_receipt (
    id            BIGSERIAL    PRIMARY KEY,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    year          SMALLINT     NOT NULL,
    billing_id    BIGINT       NOT NULL REFERENCES billing (id),
    -- 어느 수납에 대한 영수증인가. 수납 취소 시 함께 취소해야 한다
    transaction_id BIGINT      REFERENCES payment_transaction (id),
    pg_site_id    BIGINT       NOT NULL REFERENCES pg_site (id),

    -- 우리 주문번호. KCP 가 유니크를 권장한다
    order_no      VARCHAR(50)  NOT NULL,

    -- 소득공제(개인) / 지출증빙(기업). ★ 식별번호의 의미가 달라진다
    --
    -- ★ KCP 코드값(0·1)이 아니라 우리 이름으로 저장한다. 숫자로 두면 DB 를 직접 볼 때
    --   무엇인지 알 수 없고, KCP 가 코드 체계를 바꾸면 저장된 과거 값의 뜻이 흔들린다.
    --   전문에 실을 때만 code() 로 바꾼다.
    trade_purpose VARCHAR(10)  NOT NULL CHECK (trade_purpose IN ('PERSONAL', 'BUSINESS')),
    -- 소득공제면 휴대폰번호, 지출증빙이면 사업자번호
    id_info       VARCHAR(19)  NOT NULL,

    amount        INTEGER      NOT NULL CHECK (amount > 0),
    -- 공급가액·부가세를 그대로 저장한다. 나중에 다시 계산하면 절사 규칙이 달라져 어긋난다
    supply_amount INTEGER      NOT NULL,
    tax_amount    INTEGER      NOT NULL,

    status        VARCHAR(20)  NOT NULL DEFAULT 'ISSUED'
                  CHECK (status IN ('ISSUED', 'CANCELED', 'FAILED')),

    -- KCP 현금영수증 거래번호. 취소의 키다
    cash_no       VARCHAR(20),
    receipt_no    VARCHAR(20),
    issued_at     TIMESTAMPTZ,
    canceled_at   TIMESTAMPTZ,
    fail_reason   VARCHAR(200),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_cash_receipt_order ON cash_receipt (order_no);

-- 같은 수납에 유효한 영수증이 둘이면 이중 발급이다. 취소분은 제외한다 —
-- 취소 후 재발급이 실무에서 실제로 있다.
CREATE UNIQUE INDEX uq_cash_receipt_transaction
    ON cash_receipt (transaction_id)
    WHERE transaction_id IS NOT NULL AND status = 'ISSUED' AND is_deleted = FALSE;

CREATE INDEX idx_cash_receipt_billing ON cash_receipt (billing_id);

COMMENT ON TABLE cash_receipt IS
    '현금영수증. 현금성 거래(계좌이체·가상계좌)에만 발급한다 — 카드는 카드사가 처리한다';
COMMENT ON COLUMN cash_receipt.id_info IS
    '소득공제면 휴대폰번호, 지출증빙이면 사업자번호. 용도에 따라 의미가 다르다';
