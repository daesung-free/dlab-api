-- KCP 사이트코드와 결제 요청.
--
-- ═══ 사이트코드를 왜 테이블로 두나 ═══
-- branch_config.pg_merchant_code 한 칸으로는 표현되지 않는다. 실제 발급이
-- 「사업자 × 채널」로 나뉘고, 지점까지 갈리면 더 늘어난다.
--
--   대성학력개발 바이링크(온라인) / 단말기(오프라인)
--   한샘푸드     바이링크(온라인) / 단말기(오프라인)     ← 급식은 업체 명의다
--
-- ★ 급식이 업체 명의라 정산·환불 주체가 학원과 다르다. 한 칸에 뭉쳐 두면
--   급식비를 학원 코드로 결제하게 되고, 그 돈은 업체에게 가지 않는다.
--
-- ⚠️ 지점 축을 NULL 허용으로 둔다. 9/11 목록은 지점별로 쪼개져 있었고 9/16 발급분은
--    사업자별 2개뿐이라 아직 어느 쪽인지 확정되지 않았다 — NULL 이면 전 지점 공용이고,
--    지점별로 받게 되면 행을 추가하면 된다. 어느 쪽이든 스키마를 다시 고치지 않는다.
CREATE TABLE pg_site (
    id           BIGSERIAL    PRIMARY KEY,

    -- 비우면 전 지점 공용
    academy_id   BIGINT       REFERENCES academy (id),

    -- 무엇에 대한 결제인가. 급식은 업체 명의라 코드가 다르다
    purpose      VARCHAR(20)  NOT NULL CHECK (purpose IN ('TUITION', 'MEAL')),
    -- 어떻게 받는가. BUYLINK=결제 URL 문자, TERMINAL=데스크 카드단말기
    channel      VARCHAR(20)  NOT NULL CHECK (channel IN ('BUYLINK', 'TERMINAL')),

    -- KCP 사이트코드(5자리). ★ 비밀값이 아니다 — 인증은 인증서·개인키가 한다
    site_cd      VARCHAR(10)  NOT NULL,
    -- 화면에 보일 이름. "대성학력개발 바이링크" 처럼 발급서 표기를 그대로 둔다
    display_name VARCHAR(100) NOT NULL,
    -- 급식업체 명의면 그 업체. 학원 명의면 비어 있다
    vendor_id    BIGINT       REFERENCES meal_vendor (id),

    active       BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 같은 지점·용도·채널에 코드가 둘이면 어느 것으로 결제할지 정해지지 않는다.
-- 지점이 NULL 인 공용 행과 지점별 행은 공존할 수 있다(지점별이 우선).
CREATE UNIQUE INDEX uq_pg_site_scope
    ON pg_site (COALESCE(academy_id, 0), purpose, channel)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE pg_site IS
    'KCP 사이트코드. 사업자(학원/급식업체) × 채널(바이링크/단말기)로 갈린다';
COMMENT ON COLUMN pg_site.academy_id IS
    'NULL 이면 전 지점 공용. 지점별 코드를 받으면 행을 추가한다 — 지점별이 공용보다 우선';

-- ═══ 결제 요청 ═══
--
-- ★ 결제는 즉시 끝나지 않는다. 바이링크는 문자를 보낸 뒤 고객이 누를 때까지,
--   가상계좌는 입금할 때까지 기다린다. 그 사이 상태를 담을 자리가 없었다 —
--   billing 에는 PENDING(미납)과 PAID(완납)뿐이라 "링크는 보냈는데 아직 안 낸" 을
--   표현할 수 없고, 데스크는 링크를 또 보내게 된다.
--
-- ★★ 완료는 이 표가 아니라 Webhook 이 확정한다. KCP 가이드가 명시한다 —
--    "URL 생성 응답만으로 주문처리 하지 말 것". 생성 성공은 링크가 만들어졌다는 뜻일 뿐이다.
CREATE TABLE payment_request (
    id            BIGSERIAL    PRIMARY KEY,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    year          SMALLINT     NOT NULL,
    billing_id    BIGINT       NOT NULL REFERENCES billing (id),
    pg_site_id    BIGINT       NOT NULL REFERENCES pg_site (id),

    -- KCP 에 넘긴 우리 주문번호(ordr_idxx). Webhook 이 이 값으로 돌아온다
    order_no      VARCHAR(40)  NOT NULL,
    amount        INTEGER      NOT NULL CHECK (amount > 0),
    pay_method    VARCHAR(10)  NOT NULL CHECK (pay_method IN ('CARD', 'BANK', 'MOBX')),

    status        VARCHAR(20)  NOT NULL DEFAULT 'CREATED'
                  CHECK (status IN ('CREATED', 'PAID', 'CANCELED', 'EXPIRED', 'FAILED')),

    -- 생성된 결제 URL 과 KCP 가 준 식별자(거래조회·사용중지에 쓴다)
    pay_url       VARCHAR(500),
    url_reg_id    VARCHAR(100),
    expire_at     TIMESTAMPTZ,

    -- 승인 후 채워진다. tno 는 KCP 거래번호로 취소·조회의 키다
    tno           VARCHAR(20),
    approved_at   TIMESTAMPTZ,
    -- 카드사·카드번호 뒷자리 등 영수증에 쓸 값. 원문을 그대로 두지 않고 필요한 것만 남긴다
    pay_detail    VARCHAR(200),
    fail_reason   VARCHAR(200),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 주문번호는 우리가 만들고 KCP 가 그대로 돌려준다. 겹치면 어느 결제인지 알 수 없다.
CREATE UNIQUE INDEX uq_payment_request_order ON payment_request (order_no);

-- ★★ 멱등의 근거. KCP 는 우리가 result=0000 을 돌려줄 때까지 최대 10번 재전송한다.
--    같은 거래번호로 수납이 두 번 잡히면 그 학생은 두 번 낸 것으로 기록된다.
CREATE UNIQUE INDEX uq_payment_request_tno
    ON payment_request (tno) WHERE tno IS NOT NULL;

CREATE INDEX idx_payment_request_billing ON payment_request (billing_id);

COMMENT ON TABLE payment_request IS
    '결제 요청. 생성 시점과 완료 시점이 떨어져 있어 그 사이 상태를 담는다';
COMMENT ON COLUMN payment_request.tno IS
    'KCP 거래번호. Webhook 재전송(최대 10회)에 대한 멱등 키이고 취소·조회의 기준이다';
