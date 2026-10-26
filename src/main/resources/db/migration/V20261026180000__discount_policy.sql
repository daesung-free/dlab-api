-- 할인 정책 마스터 (1016 Q3·Q4).
--
-- ★★ 왜 데이터로 두는가 — 종류 목록을 모르는 채로 착수할 수 있어야 한다.
--   1016 에서 「할인 종류 전체 목록 + 비율/금액 구분」과 「월별로 달라지는 규칙」을 물었고
--   10/21 답변서에서도 대기로 남았다. 코드에 박으면 답이 올 때까지 교습비 청구가 멈춘다.
--   penalty_rule · scholarship_cancel_rule 과 같은 방식이다 — 행만 채우면 돈다.
--
-- ★ 비율과 금액을 한 칸에 담지 않는다. 0820 규정은 비율(10·20·30·50·70%)이지만
--   1016 이 「비율/금액 구분」을 물은 것은 금액 할인이 있다는 뜻으로 읽힌다.
--   한 칸에 담으면 30 이 「30%」인지 「30원」인지 알 수 없다.
--
-- ★ 월 범위를 둔다. 1021 답변서에 「1월 30% · 2~3월 20%」 같은 예시가 있어
--   같은 할인이 달마다 다른 비율로 적용된다. 비워 두면 연중 동일하다.
--   ⚠️ 그 예시값은 「예시」라고 명시됐으므로 시드로 넣지 않는다.
--
-- ⚠️ 독서실비에는 할인이 붙지 않는다(0820). 적용 대상을 항목 유형으로 제한하고,
--   판정은 BillingItemType.isDiscountable() 한 곳을 쓴다 — 통과시키면 환불 차감이
--   정가보다 작아져 학원이 손해를 본다.

CREATE TABLE discount_policy (
    id                BIGSERIAL    PRIMARY KEY,
    year              SMALLINT     NOT NULL,
    -- NULL 이면 전 지점 공통. 값이 있으면 그 지점만 (지점별로 다른 할인이 있다)
    academy_id        BIGINT       REFERENCES academy (id),

    -- ★ enum 이 아니라 문자열이다. 종류가 늘어나도 마이그레이션이 필요 없다
    code              VARCHAR(40)  NOT NULL,
    name              VARCHAR(100) NOT NULL,

    -- RATE | AMOUNT
    discount_type     VARCHAR(10)  NOT NULL,
    -- RATE 면 퍼센트(0~100), AMOUNT 면 원
    discount_value    INT          NOT NULL,

    -- 적용 항목. 비워 두면 할인 가능한 항목 전부
    target_item_type  VARCHAR(20),

    -- 적용 월 범위(1~12). 둘 다 NULL 이면 연중 동일
    apply_from_month  SMALLINT,
    apply_to_month    SMALLINT,

    -- ★ 기본 꺼짐. 돈이 걸린 값이라 확인 전에는 돌지 않게 한다(penalty_rule 과 같다)
    active            BOOLEAN      NOT NULL DEFAULT FALSE,
    note              VARCHAR(500),

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_discount_policy_type
        CHECK (discount_type IN ('RATE', 'AMOUNT')),
    -- 비율은 0~100 을 넘을 수 없다. 100 을 넘으면 청구액이 음수가 된다
    CONSTRAINT ck_discount_policy_rate_range
        CHECK (discount_type <> 'RATE' OR discount_value BETWEEN 0 AND 100),
    CONSTRAINT ck_discount_policy_amount_positive
        CHECK (discount_value >= 0),
    -- 한쪽만 채우면 범위가 성립하지 않는다
    CONSTRAINT ck_discount_policy_month_pair
        CHECK ((apply_from_month IS NULL) = (apply_to_month IS NULL)),
    CONSTRAINT ck_discount_policy_month_range
        CHECK (apply_from_month IS NULL
               OR (apply_from_month BETWEEN 1 AND 12
                   AND apply_to_month BETWEEN 1 AND 12
                   AND apply_from_month <= apply_to_month))
);

-- ★ 같은 (연도·지점·코드·월범위) 가 둘이면 어느 비율을 쓸지 정해지지 않는다.
--   월 범위가 NULL 인 행과 값이 있는 행은 공존할 수 있다(연중 기본 + 특정 월 예외).
CREATE UNIQUE INDEX uq_discount_policy
    ON discount_policy (year, COALESCE(academy_id, 0), code,
                        COALESCE(apply_from_month, 0), COALESCE(apply_to_month, 0))
    WHERE is_deleted = FALSE;

CREATE INDEX ix_discount_policy_lookup
    ON discount_policy (year, academy_id, active)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE discount_policy IS
    '교습비 할인 정책. 종류·비율/금액·월별 규칙을 데이터로 둔다(1016 Q3·Q4 대기 중에도 착수 가능).';
COMMENT ON COLUMN discount_policy.active IS
    '기본 꺼짐. 돈이 걸린 값이라 확인 전에는 돌지 않는다.';

-- 0820 규정의 비율 5종. ⚠️ active=FALSE 다 — 「이것이 전부인가」가 확인되면 켠다.
INSERT INTO discount_policy (year, academy_id, code, name, discount_type, discount_value,
                             target_item_type, active, note, created_by)
SELECT 2026, NULL, 'RATE_' || r, r || '% 할인', 'RATE', r, 'TUITION', FALSE,
       '0820 교습비 및 환불규정의 할인 단계. 종류 목록 확인 전까지 비활성.', 0
FROM (VALUES (10), (20), (30), (50), (70)) AS v(r);
