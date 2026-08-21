-- V20260821_1700: 급식업체 · 단가 (0820 규정 / F-4.5)
--
-- 지금 급식 도메인에 금액 개념이 아예 없다. 신청·취소만 있고 "얼마인가"가 없어서
-- 결제도 환불도 붙을 자리가 없었다.
--
-- ★ 업체와 단가가 지점마다 다르다:
--     디온푸드 7,700 (분당·이매·김포·일산·광명·목동·송파)
--     니즈푸드 7,700 (동탄) · 한샘푸드 7,700 (부천) · 한끼애 7,700 (대전)
--     성림푸드 8,000 (대구)   ← 여기만 단가가 다르다
--   규정에도 "급식업체, 급식비는 변경될 수 있고, 지점별로 상이합니다"라고 명시돼 있다.

-- ─────────────────────────────────────────────────────────────
-- 1. 급식업체
--
-- ★ 지점에 속하지 않는다. 디온푸드 한 곳이 7개 지점을 담당하므로 업체를 지점마다
--   복제하면 연락처를 고칠 때 7군데를 고쳐야 하고, 한 곳만 빠뜨려도 알 수 없다.
--
-- ★ 연락처를 지금 둔다. 클라이언트가 "환불정보를 업체에 자동 발송"을 요청했는데
--   (지금은 단톡방 수기), 발송 수단이 아직 미확정이라 자리만 만들어 둔다.
--   ⚠️ 환불계좌는 개인정보라 제3자 제공 동의 범위 확인이 먼저다 — 발송 구현은 그 뒤다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE meal_vendor (
    id            BIGSERIAL   PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,

    -- 환불정보 자동 발송 수신처. 수단 미확정이라 비어 있을 수 있다
    contact_name  VARCHAR(30),
    contact_phone VARCHAR(20),
    contact_email VARCHAR(120),

    active        BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_meal_vendor_name
    ON meal_vendor (name) WHERE is_deleted = FALSE;

COMMENT ON TABLE meal_vendor IS
    '급식업체. 한 업체가 여러 지점을 담당하므로 지점에 속하지 않는다';

-- ─────────────────────────────────────────────────────────────
-- 2. 지점별 업체·단가 — 기존 meal_policy 를 확장한다
--
-- ★ 테이블을 새로 만들지 않는 이유는, meal_policy 가 이미 (지점 × 연도)로
--   급식 설정을 들고 있기 때문이다(마감 D-n). 지점 급식 설정이 두 군데로 갈리면
--   한쪽만 등록된 지점이 생긴다.
--
-- ★ 연도가 이미 키에 있어 단가 인상이 연도별로 관리된다.
--   과거 주문 금액은 아래 스냅샷이 지킨다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE meal_policy ADD COLUMN vendor_id  BIGINT REFERENCES meal_vendor (id);
ALTER TABLE meal_policy ADD COLUMN unit_price INTEGER CHECK (unit_price >= 0);

COMMENT ON COLUMN meal_policy.unit_price IS
    '한 끼 단가. 점심·저녁이 같다(0820 기준). 지점별로 다르다 — 대구만 8,000';

-- ─────────────────────────────────────────────────────────────
-- 3. ★ 주문 항목에 단가를 스냅샷으로 남긴다
--
--   이게 핵심이다. 단가는 바뀔 수 있는데(규정 명시), 마스터만 보고 금액을 계산하면
--   단가를 올린 순간 <b>과거 주문 금액이 소급해서 바뀐다.</b> 이미 결제·정산이 끝난
--   달의 금액이 달라지면 맞출 방법이 없다.
--
--   청구(billing.billed_amount)에서 같은 판단을 했다.
--
--   기존 행은 단가가 없던 시절 것이라 NULL 을 허용한다 — 실데이터가 0이라 무해하다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE meal_order_item ADD COLUMN unit_price INTEGER CHECK (unit_price >= 0);

COMMENT ON COLUMN meal_order_item.unit_price IS
    '신청 시점 단가 스냅샷. 마스터를 다시 읽으면 단가 인상이 과거 주문에 소급된다';

-- ─────────────────────────────────────────────────────────────
-- 4. 업체 초기 데이터
--
-- ⚠️ 지점 연결(meal_policy)은 지점이 등록돼 있어야 걸 수 있다. 대전·대구는 아직
--    등록 전이라 그 두 곳은 지점 추가 후 관리자 화면에서 연결해야 한다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO meal_vendor (name, created_by) VALUES
    ('디온푸드', 0), ('니즈푸드', 0), ('한샘푸드', 0), ('한끼애', 0), ('성림푸드', 0);

-- 지점별 업체·단가. 없는 지점은 그냥 걸리지 않는다
UPDATE meal_policy p
SET vendor_id = v.id, unit_price = m.price
FROM academy a, meal_vendor v,
     (VALUES
        ('분당', '디온푸드', 7700), ('이매', '디온푸드', 7700), ('김포', '디온푸드', 7700),
        ('일산', '디온푸드', 7700), ('광명', '디온푸드', 7700), ('목동', '디온푸드', 7700),
        ('송파', '디온푸드', 7700),
        ('동탄', '니즈푸드', 7700),
        ('부천', '한샘푸드', 7700),
        ('대전', '한끼애',   7700),
        ('대구', '성림푸드', 8000)
     ) AS m(academy_name, vendor_name, price)
WHERE p.academy_id = a.id
  AND a.acad_nm = m.academy_name
  AND v.name = m.vendor_name
  AND p.is_deleted = FALSE;
