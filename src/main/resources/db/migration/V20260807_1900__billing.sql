-- V20260807_1900: 수납 — 청구 + 거래 (F-4.8-1 · 키오스크 3.29)
--
-- ★ 지금 만드는 이유는 키오스크 getReceiptInfo 하나 때문이다.
--   그게 요구하는 건 네 값뿐이다 — rcv_nm(청구명) · supp_amt(공급가) ·
--   rec_amt(수납액) · mi_amt(미납액). PG 없이도 낼 수 있다:
--   청구를 만들고 수납을 기록하면 미납은 뺄셈이다.
--   지금은 조회가 실패하면 키오스크가 빈 배열로 폴백해 화면만 비고 아무도 모른다.
--
-- ★ 결제(PG)는 넣지 않는다 — E-3(가맹점정보·API 스펙) 미확보.
--   상태값은 시트가 확정한 등록비·급식 공통 라이프사이클을 그대로 쓴다.
--   PENDING → ISSUED → PAID → CANCELLED / EXPIRED / REFUNDED
--
-- ★ 환불(REFUNDED)은 상태만 두고 산출은 안 한다 — 일할계산 산식(I-26)이 미확정이고
--   학원법 반환기준이라 PG 취소 API 호출로 대체할 수 없다.

-- ─────────────────────────────────────────────────────────────
-- 청구 — "누구에게 얼마를 받을 것인가"
-- ─────────────────────────────────────────────────────────────
CREATE TABLE billing (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    year            SMALLINT    NOT NULL,
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 키오스크 rcv_nm. "2026학년도 1기 교습비" 같은 표시명이다
    name            VARCHAR(100) NOT NULL,
    -- ★ 전표 통합 체계(시트)를 위해 유형을 남긴다. 등록비와 급식이 같은 PG를 쓴다
    billing_type    VARCHAR(20) NOT NULL
        CHECK (billing_type IN ('TUITION','MEAL','LECTURE','ETC')),

    -- 정가. 키오스크 supp_amt
    supplied_amount INTEGER     NOT NULL,
    -- 할인(장학 등). 정가에서 빼 청구액을 만든다
    discount_amount INTEGER     NOT NULL DEFAULT 0,
    -- 실제 청구액 = 정가 − 할인. 저장해둔다 — 나중에 할인 정책이 바뀌어도
    -- 과거 청구액이 소급해서 바뀌면 안 된다
    billed_amount   INTEGER     NOT NULL,

    due_date        DATE,

    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ISSUED','PAID','CANCELLED','EXPIRED','REFUNDED')),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_billing_amount CHECK (supplied_amount >= 0 AND discount_amount >= 0
                                        AND billed_amount >= 0)
);

-- getReceiptInfo가 학생 단건으로 훑는다
CREATE INDEX idx_billing_enrollment
    ON billing (enrollment_id) WHERE is_deleted = FALSE;
-- 수납현황·미납자 추출이 지점·기간으로 훑는다
CREATE INDEX idx_billing_lookup
    ON billing (academy_id, year, status) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 거래 — "실제로 얼마가 들어왔는가"
--
-- ★ 청구 1건에 여러 건이 붙는다(분납·부분입금). 그래서 청구에 수납액 컬럼을 두지 않고
--   여기서 합산한다 — 컬럼으로 두면 거래와 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE payment_transaction (
    id         BIGSERIAL   PRIMARY KEY,
    billing_id BIGINT      NOT NULL REFERENCES billing (id),

    amount     INTEGER     NOT NULL CHECK (amount > 0),
    method     VARCHAR(20) NOT NULL CHECK (method IN ('CARD','VBANK','CASH','TRANSFER','ETC')),
    paid_at    TIMESTAMPTZ NOT NULL,

    -- PG 거래 식별자. 자체 PG 연동(E-3) 전까지는 비어 있다
    pg_tid     VARCHAR(100),

    -- 취소된 거래는 지우지 않는다 — 수납 이력이 사라지면 정산 추적이 끊긴다
    canceled_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_payment_billing
    ON payment_transaction (billing_id) WHERE canceled_at IS NULL AND is_deleted = FALSE;

COMMENT ON TABLE billing IS '청구. 키오스크 getReceiptInfo(3.29)가 이 데이터를 읽는다';
COMMENT ON COLUMN billing.billed_amount IS
    '정가 − 할인. 저장해둔다 — 할인 정책이 바뀌어도 과거 청구액이 소급 변경되면 안 된다';
COMMENT ON TABLE payment_transaction IS
    '수납 거래. 청구 1건에 여러 건(분납). 수납액을 청구 컬럼에 두지 않고 여기서 합산한다';
