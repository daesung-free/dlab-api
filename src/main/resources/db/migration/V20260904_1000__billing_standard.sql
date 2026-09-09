-- V20260904_1000: 청구기준 마스터 (F-4.10-5)
--
-- 요구사항: "교습비·특강비·환불 기준 관리, 4.8 수납현황과 연계"
-- DSA 화면: 관리자 > 수납관리 > 청구기준 관리 — 상태필터 + 청구기준목록 + 수정/삭제
--
-- ★ 지금까지 tuition_price(학년 × 좌석유형 단가표) 하나만 있었다.
--   그건 청구기준의 "교습비" 축이고, 특강비·등록비·급식비가 통째로 빠져 있었다.
--   화면이 요구하는 것은 "무엇을 · 얼마에 · 언제 청구하는가"의 목록이라 카탈로그를 둔다.
--
-- ★ 금액을 두 방식으로 갖는다 (amount_source).
--   특강비·등록비는 값 하나로 끝나지만(FIXED),
--   교습비는 학년 × 좌석유형으로 갈려서(N수 750,000 / 재학생 490,000 / 1인실 지점별)
--   한 칸에 넣을 수 없다. 그래서 교습비 행은 금액을 갖지 않고
--   tuition_price 단가표를 가리킨다(PRICE_MATRIX).
--   억지로 대표값 하나를 박아두면 화면 금액과 실제 청구액이 갈린다.

-- ─────────────────────────────────────────────────────────────
-- 등록비 항목 추가
--
-- F-4.8-1이 "카드·가상계좌·등록비 통합 매출"을 요구하는데 항목에 등록비가 없었다.
-- 환불은 NONE — 학원법 반환기준은 교습비에 대한 것이고 등록비 반환 규정은
-- 아직 받지 못했다(받으면 BillingItemType 쪽에서 산식을 붙인다).
-- ─────────────────────────────────────────────────────────────
ALTER TABLE billing_item DROP CONSTRAINT IF EXISTS billing_item_item_type_check;

ALTER TABLE billing_item ADD CONSTRAINT billing_item_item_type_check
    CHECK (item_type IN ('TUITION','STUDY_ROOM','MEAL','LECTURE','REGISTRATION','ETC'));

-- ─────────────────────────────────────────────────────────────
-- 청구기준
-- ─────────────────────────────────────────────────────────────
CREATE TABLE billing_standard (
    id            BIGSERIAL   PRIMARY KEY,

    -- NULL = 전 지점 공통. 지점 행이 있으면 그 지점에서는 그것만 쓴다
    -- (tuition_price·terms·exam_master와 같은 규약)
    academy_id    BIGINT      REFERENCES academy (id),
    year          SMALLINT    NOT NULL,

    -- 화면·전표에 노출되는 표시용 코드(BL-TU-01 등). 사람이 정한다 —
    -- 내부 id를 쓰면 전표에 순번이 드러나고 지점 간 비교도 안 된다
    code          VARCHAR(30) NOT NULL,

    item_type     VARCHAR(20) NOT NULL
        CHECK (item_type IN ('TUITION','STUDY_ROOM','MEAL','LECTURE','REGISTRATION','ETC')),

    name          VARCHAR(100) NOT NULL,
    -- 기수(1기·2기…). 교습비는 기수마다 다시 청구된다. 없으면 NULL
    round_name    VARCHAR(20),

    -- FIXED = amount 그대로 / PRICE_MATRIX = tuition_price 단가표에서 나온다
    amount_source VARCHAR(20) NOT NULL DEFAULT 'FIXED'
        CHECK (amount_source IN ('FIXED','PRICE_MATRIX')),
    -- PRICE_MATRIX면 NULL이다. 대표값을 박아두면 화면과 실제 청구액이 갈린다
    amount        INTEGER,

    -- "매월 25일" · "등록 시" · "개강 3일 전" 처럼 자유 문구다.
    -- ★ 날짜 규칙으로 정형화하지 않는다 — 실제 값이 "등록 시"처럼 날짜가 아닌 것이
    --   섞여 있어서, 규칙으로 강제하면 그 행을 아예 못 넣는다
    due_desc      VARCHAR(50),

    -- 수납 수단 안내. 0803에 디랩 자체 PG로 단일화돼 값은 카드/가상계좌뿐이다
    -- (목업의 '대성전산'·'급식업체 PG'는 그 확정으로 폐기된 값이다)
    payment_method VARCHAR(20)
        CHECK (payment_method IN ('CARD','VBANK','CASH','TRANSFER','ETC')),

    -- 화면 상태필터. 지난 기수 기준은 지우지 않고 내려둔다 —
    -- 지우면 그 기수 청구가 어느 기준으로 나갔는지 추적이 끊긴다
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order    SMALLINT    NOT NULL DEFAULT 0,
    memo          VARCHAR(200),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,

    -- PRICE_MATRIX는 금액을 갖지 않고, FIXED는 반드시 갖는다.
    -- 애플리케이션에서만 막으면 시드·수기 INSERT가 그대로 뚫린다
    CONSTRAINT ck_billing_standard_amount CHECK (
        (amount_source = 'PRICE_MATRIX' AND amount IS NULL)
     OR (amount_source = 'FIXED' AND amount IS NOT NULL AND amount >= 0)
    )
);

-- 같은 지점·연도에서 코드는 하나다. COALESCE로 공통(NULL)과 지점 행을 함께 묶는다 —
-- NULL은 유니크 비교에서 서로 다르게 취급돼 공통 행이 몇 개든 들어간다
CREATE UNIQUE INDEX uq_billing_standard_code
    ON billing_standard (year, code, COALESCE(academy_id, 0)) WHERE is_deleted = FALSE;

CREATE INDEX idx_billing_standard_scope
    ON billing_standard (year, academy_id, item_type, sort_order)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE billing_standard IS
    '청구기준 마스터(F-4.10-5). 교습비 행은 금액을 갖지 않고 tuition_price를 가리킨다';
COMMENT ON COLUMN billing_standard.amount_source IS
    'FIXED=amount / PRICE_MATRIX=tuition_price 학년×좌석유형 단가표';
COMMENT ON COLUMN billing_standard.due_desc IS
    '청구 시점 안내 문구. "등록 시"처럼 날짜가 아닌 값이 있어 정형화하지 않는다';
