-- V20260807_1800: 급식 주문 구조 (F-4.5 · A-9)
--
-- ★ 신청 1건 = 1행이던 meal_application을 주문 + 항목 2단으로 바꾼다.
--   요구사항이 "월말에 다음 달 한 달치 일괄 신청"(A-9)이고 결제도 한 달치를 한 번에
--   하므로, 결제가 붙을 자리는 주문이다. 시트 데이터 항목도 meal_orders /
--   meal_order_items로 지정돼 있고, 앱 취소 API가 항목 단위다
--   (DELETE /meals/orders/{id}/items/{itemId}).
--
--   신청을 넣는 경로가 아직 없어 실데이터가 0이므로 그냥 갈아엎는다.

DROP TABLE IF EXISTS meal_application;

-- ─────────────────────────────────────────────────────────────
-- 주문 — 한 달치 묶음. 결제가 붙는 단위다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_order (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    -- year 없음 — enrollment이 이미 연도를 내포한다(V1 머리말 규칙)
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 대상 월. 'yyyy-MM'이 아니라 그 달 1일로 넣는다 — 범위 조회가 쉽다
    target_month  DATE        NOT NULL,

    -- ★ 상태는 시트가 확정한 결제·수납 공통 라이프사이클을 따른다
    --   PENDING → ISSUED → PAID → CANCELLED / EXPIRED / REFUNDED
    --   결제(E-3)가 없어 지금은 PENDING에서 움직이지 않는다.
    --   결제가 붙으면 나머지 전이가 이어진다 — 값을 새로 만들지 말 것
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ISSUED','PAID','CANCELLED','EXPIRED','REFUNDED')),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_meal_order_lookup
    ON meal_order (academy_id, target_month) WHERE is_deleted = FALSE;
CREATE INDEX idx_meal_order_enrollment
    ON meal_order (enrollment_id, target_month) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 항목 — 날짜 × 끼니. 취소가 여기 단위다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_order_item (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES meal_order (id),
    meal_date   DATE        NOT NULL,
    meal_type   VARCHAR(10) NOT NULL CHECK (meal_type IN ('LUNCH','DINNER')),

    -- ★ 취소를 물리 삭제하지 않는다. 앱 취소는 PG 환불 대상이고 관리자 취소는
    --   기간 제한이 없어, "언제 누가 어느 경로로 취소했나"가 정산 근거가 된다
    canceled_at TIMESTAMPTZ,
    cancel_path VARCHAR(10) CHECK (cancel_path IN ('APP','DESK','CLOSURE')),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_meal_item_order ON meal_order_item (order_id);
-- getMealApplyYN(당일 끼니) · getMealApplyStdInfo(월별)가 이 순서로 훑는다
CREATE INDEX idx_meal_item_date
    ON meal_order_item (meal_date, meal_type) WHERE canceled_at IS NULL AND is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 급식 중단일 — ★ 공휴일과 다른 테이블이다
--   학원은 여는데 급식만 안 하는 날이라 holiday에 섞으면 그날 학습계획·출결까지 죽는다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_closure (
    id           BIGSERIAL   PRIMARY KEY,
    academy_id   BIGINT      NOT NULL REFERENCES academy (id),
    year         SMALLINT    NOT NULL,
    closure_date DATE        NOT NULL,
    -- 학원 휴무 / 급식업체 휴무 / 단축수업 / 모의고사 / 자체 행사 (화면 칩)
    reason       VARCHAR(50) NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_meal_closure
    ON meal_closure (academy_id, closure_date) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 정책 — 신청 마감 D-n
--   화면이 드롭다운(1/2/3/5/7)으로 고르게 한다. 상수로 박으면 안 된다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_policy (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    deadline_days SMALLINT    NOT NULL DEFAULT 3,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_meal_policy
    ON meal_policy (academy_id, year) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 월 접수기간 — 대상월별로 다르다(5/18~27에 6월분을 받는다)
--   그래서 정책과 한 테이블에 못 넣는다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_order_window (
    id           BIGSERIAL   PRIMARY KEY,
    academy_id   BIGINT      NOT NULL REFERENCES academy (id),
    year         SMALLINT    NOT NULL,
    target_month DATE        NOT NULL,
    starts_on    DATE        NOT NULL,
    ends_on      DATE        NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_meal_window_range CHECK (ends_on >= starts_on)
);

CREATE UNIQUE INDEX uq_meal_order_window
    ON meal_order_window (academy_id, target_month) WHERE is_deleted = FALSE;

COMMENT ON TABLE meal_order IS '급식 주문(월 단위). 결제가 붙는 단위다';
COMMENT ON COLUMN meal_order.status IS
    '시트 확정 라이프사이클. 결제 전까지 PENDING에서 움직이지 않는다';
COMMENT ON TABLE meal_order_item IS '날짜×끼니. 취소가 이 단위다';
COMMENT ON COLUMN meal_order_item.cancel_path IS 'APP(마감 전 학생) / DESK(관리자 즉시) / CLOSURE(중단일 등록)';
COMMENT ON TABLE meal_closure IS '급식 중단일. 공휴일과 별개다 — 학원은 열고 급식만 안 한다';
COMMENT ON TABLE meal_order_window IS '월 접수기간. 기간 밖에는 신청 화면이 열리지 않는다(F-4.5)';
