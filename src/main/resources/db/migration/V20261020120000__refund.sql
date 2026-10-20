-- 환불 기록·집행 (0820 교습비·환불 규정 + 디랩 10/12 회신 ①②③)
--
-- 지금까지 RefundCalculator(계산기)만 있었고 **기록도 집행도 없었다.**
-- 화면에서 수납을 취소하면 우리 DB 에 취소 표시만 되고 PG 에는 아무 일도 일어나지
-- 않았다 — 실연동 뒤에 그 상태면 「취소했는데 환불이 안 된」 건이 쌓인다.
--
-- ★ 디랩 10/12 회신으로 방식이 정해졌다
--     카드 결제분  → 카드 취소(PG 부분취소)
--     가상계좌 입금분 → 계좌 이체   (우리가 이체하지 않는다 — 대상으로 적재한다)
--
-- ⚠️ 급식비는 이 경로를 타지 않는다. 급식은 업체 명의 TID 로 매출이 잡혀
--    (0911 MID 구조) 학원비와 같은 경로로 취소를 걸면 남의 사업자 매출을 우리가
--    취소하는 꼴이 된다. 처리 주체가 미회신이라(확인 14) 서비스에서 거절한다.

CREATE TABLE refund (
    id                  BIGSERIAL    PRIMARY KEY,
    academy_id          BIGINT       NOT NULL REFERENCES academy (id),
    year                SMALLINT     NOT NULL,
    billing_id          BIGINT       NOT NULL REFERENCES billing (id),

    -- 계산 입력. 나중에 "왜 이 금액인가"를 재현하려면 입력도 남아야 한다
    teaching_days       SMALLINT     NOT NULL,
    used_days           SMALLINT     NOT NULL,

    -- 계산 결과. ★ 음수일 수 있다 = 추가 징수다(0820 규정 「정상가 기준 차감」).
    --   0 에서 자르지 말 것 — 받아야 할 돈이 사라지고 데스크는 화면에서 알 방법이 없다
    refund_amount       INTEGER      NOT NULL,

    -- ★ 계산 근거를 항목별로 남긴다. 금액만 남기면 구간·일할·정상가차감이 얽혀
    --   나중에 재현이 안 되고, 클라이언트가 요청한 「추가결제 상세 내역 출력」도 이걸 쓴다
    detail              TEXT         NOT NULL,

    method              VARCHAR(20)  NOT NULL
                        CHECK (method IN ('CARD_CANCEL', 'BANK_TRANSFER', 'NONE')),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'DONE', 'FAILED', 'ADDITIONAL_PAYMENT')),
    fail_reason         VARCHAR(500),

    -- 카드 취소를 실제로 건 거래. 가상계좌 환불이면 비어 있다
    transaction_id      BIGINT       REFERENCES payment_transaction (id),
    pg_canceled_tid     VARCHAR(100),

    -- ⚠️ 환불계좌는 여기 두지 않는다.
    --   「앱에서 학부모·학생이 직접 입력」으로 확정됐지만(확인 13), 계좌번호를 우리가
    --   보관하게 되므로 **개인정보 동의 항목에 「환불계좌」가 들어가야** 한다.
    --   그 문구가 미회신이라(10/13 미회신 1번) 수집 자체를 시작하지 않는다 —
    --   약관은 수정이 아니라 버전을 올리는 구조라, 동의 없이 받아두면 되돌릴 수 없다.

    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 한 청구에 환불이 두 번 집행되면 안 된다. 실패분은 다시 걸 수 있어야 하므로
-- 성공·대기 상태만 막는다
CREATE UNIQUE INDEX uq_refund_billing_active
    ON refund (billing_id)
    WHERE is_deleted = FALSE AND status IN ('PENDING', 'DONE', 'ADDITIONAL_PAYMENT');

CREATE INDEX idx_refund_academy_year ON refund (academy_id, year, created_at DESC);
-- 계좌 이체 대상을 뽑는 화면이 이걸 쓴다 — 데스크가 매일 봐야 하는 목록이다
CREATE INDEX idx_refund_pending_transfer
    ON refund (status, method)
    WHERE status = 'PENDING' AND method = 'BANK_TRANSFER';

COMMENT ON COLUMN refund.refund_amount IS
    '환불액. 음수면 추가 징수다(정상가 기준 차감이 납부액을 넘은 경우) — 0 으로 자르지 말 것';
COMMENT ON COLUMN refund.method IS
    'CARD_CANCEL=카드 취소 / BANK_TRANSFER=계좌 이체 대상 / NONE=환불액 0 또는 추가 징수';
