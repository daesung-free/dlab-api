-- V20260821_1400: 청구 항목 분리 + 환불 산식 (0820 규정 / I-26)
--
-- ★ 청구를 두 줄로 쪼개지 않는다. 아래에 항목을 단다.
--
--   750,000원을 교습비/독서실비 두 청구로 나누면 키오스크 getReceiptInfo 영수증이
--   한 줄에서 두 줄로 바뀐다(3.29). 청구는 그대로 두고 항목만 달면 영수증은 안 바뀌고
--   환불 계산만 항목 단위로 돈다 — 키오스크 무영향이다.
--
-- ★ 항목을 나누는 이유는 "환불 산식이 항목마다 다르기 때문"이다.
--     교습비   구간 — 이용기간 1/3까지 2/3 환불, 1/2까지 1/2, 1/2 이후 없음 (학원법 기준)
--     독서실비 일할 — 사용한 일수만큼 차감 후 환불
--   한 값으로 두면 퇴원 정산에서 다시 가를 방법이 없다.

-- ─────────────────────────────────────────────────────────────
-- 1. 청구가 "언제분"인지
--
-- ★ 환불은 "퇴원하는 달"을 기준으로 계산한다. 그런데 지금 billing 에는 표시명과
--   납부기한밖에 없어서 <b>어느 달 이용분인지 알 수 없다</b> — 교습일수를 못 찾고
--   사용 비율도 못 낸다.
--
-- ★ 청구 1건 = 한 달분이다. 최초 입학 때 다음 달까지 함께 받는 경우는 청구를 2건
--   만든다 — 한 건에 두 달을 담으면 그중 한 달만 환불하는 계산이 성립하지 않는다.
--
--   급식·특강은 달 단위가 아니라 NULL 을 허용한다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE billing ADD COLUMN service_year  SMALLINT;
ALTER TABLE billing ADD COLUMN service_month SMALLINT CHECK (service_month BETWEEN 1 AND 12);

COMMENT ON COLUMN billing.service_month IS
    '이용 월. 환불 계산이 이 값으로 교습일수를 찾는다. 급식·특강은 NULL';

-- ─────────────────────────────────────────────────────────────
-- 2. 청구 항목
--
-- ★ 정가(supplied)와 청구액(billed)을 항목마다 따로 든다.
--   환불에서 "정상가 기준 차감"을 해야 하는데(아래), 정가를 안 들고 있으면
--   할인받은 학생의 차감액을 계산할 수 없다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE billing_item (
    id              BIGSERIAL   PRIMARY KEY,
    billing_id      BIGINT      NOT NULL REFERENCES billing (id),

    item_type       VARCHAR(20) NOT NULL
        CHECK (item_type IN ('TUITION','STUDY_ROOM','MEAL','LECTURE','ETC')),

    -- 정가. 할인 전 금액이고 환불 차감의 기준이다
    supplied_amount INTEGER     NOT NULL CHECK (supplied_amount >= 0),
    -- 할인. 독서실비는 항상 0이다 — 규정상 할인이 없다
    discount_amount INTEGER     NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    -- 정가 − 할인. 실제로 받은 금액의 기준
    billed_amount   INTEGER     NOT NULL CHECK (billed_amount >= 0),

    sort_order      SMALLINT    NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 청구에 같은 항목이 두 번 들어가면 합계가 어긋난다
CREATE UNIQUE INDEX uq_billing_item
    ON billing_item (billing_id, item_type) WHERE is_deleted = FALSE;

CREATE INDEX idx_billing_item_billing
    ON billing_item (billing_id, sort_order) WHERE is_deleted = FALSE;

COMMENT ON COLUMN billing_item.supplied_amount IS
    '정가. 환불 차감이 "정상가 기준"이라(0820 규정) 할인받은 건도 이 값을 쓴다';
COMMENT ON COLUMN billing_item.item_type IS
    'TUITION=구간 환불, STUDY_ROOM=일할 환불. 산식이 달라서 나눠 둔다';

-- ─────────────────────────────────────────────────────────────
-- 3. 환불 계산 결과
--
-- ★ 계산 결과를 남기는 이유는 "왜 이 금액인가"에 답하기 위해서다.
--   규정이 구간·일할·정상가차감으로 얽혀 있어, 금액만 남기면 나중에 재현이 안 된다.
--   클라이언트도 "추가결제 시 상세 내역 출력"을 요청했다.
--
-- ★ refund_amount 가 음수일 수 있다 — 그게 곧 추가 징수다.
--   할인받은 학생이 이용기간을 넘겨 퇴원하면 "정상가 기준 차감"이 납부액보다 커진다.
--   규정의 "차감금액이 부족할 경우 원칙상 추가금 재결제"가 이 경우다.
--   0에서 자르면 그 사실이 사라지므로 자르지 않는다.
--
-- ⚠️ 실제 환불·재결제 실행(PG)은 아직 없다. 여기까지는 계산과 근거 기록이다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE refund_calculation (
    id                BIGSERIAL   PRIMARY KEY,
    academy_id        BIGINT      NOT NULL REFERENCES academy (id),
    year              SMALLINT    NOT NULL,
    billing_id        BIGINT      NOT NULL REFERENCES billing (id),

    -- 퇴원(효력)일. 소급 처리가 실제로 있어 처리일과 구분한다
    withdrawal_date   DATE        NOT NULL,
    -- 그 달 교습일수와 실제 사용일수. 재현에 필요하다
    teaching_days     SMALLINT    NOT NULL CHECK (teaching_days BETWEEN 1 AND 31),
    used_days         SMALLINT    NOT NULL CHECK (used_days >= 0),

    -- 납부액 − 차감액. 음수면 추가 징수다
    refund_amount     INTEGER     NOT NULL,
    -- 사람이 읽을 근거. 상세 내역 출력이 이걸 쓴다
    detail            TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_refund_calculation_billing
    ON refund_calculation (billing_id) WHERE is_deleted = FALSE;

COMMENT ON COLUMN refund_calculation.refund_amount IS
    '음수면 추가 징수다. 할인받고 이용기간을 넘겨 퇴원하면 정상가 차감이 납부액을 넘는다';
