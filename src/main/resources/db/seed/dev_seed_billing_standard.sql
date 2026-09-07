-- 청구기준 시드 (F-4.10-5) — 개발·화면 확인용
--
-- ★ db/seed 에 있는 이유는 다른 시드와 같다. 마이그레이션에 넣으면 테스트 DB에도
--   심어져 테스트가 스스로 만드는 데이터와 충돌한다.
--
-- 전 지점 공통 행만 넣는다(academy_id IS NULL). 지점별로 다른 값은 관리자 화면에서
-- 지점 행을 만들면 그 지점에서는 공통본 대신 그것이 쓰인다.
--
-- ⚠️ 금액은 0820 규정에서 확인된 것만 실제 값이고, 특강비·등록비는 **임시값**이다.
--    규정을 못 받은 항목이라 화면 확인용이지 운영에 그대로 쓰면 안 된다.

-- ─────────────────────────────────────────────────────────────
-- 교습비 — 금액을 갖지 않는다(PRICE_MATRIX).
--   학년 × 좌석유형으로 갈려서(N수 750,000 / 재학생 490,000 / 1인실 지점별)
--   한 칸에 못 넣는다. 실제 금액은 tuition_price 단가표에서 나온다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO billing_standard (academy_id, year, code, item_type, name, round_name,
                              amount_source, amount, due_desc, payment_method,
                              active, sort_order, memo, created_by)
SELECT NULL, 2026, v.code, v.item_type, v.name, v.round_name,
       v.amount_source, v.amount, v.due_desc, v.payment_method,
       TRUE, v.sort_order, v.memo, 0
FROM (VALUES
    ('BL-TU-01', 'TUITION',      '정규 교습비',   '1기', 'PRICE_MATRIX', NULL::INTEGER,
     '매월 25일',   'VBANK', 1::SMALLINT, '금액은 학년·좌석유형 단가표에서 나온다'),
    ('BL-RG-01', 'REGISTRATION', '신규 등록비',   NULL,  'FIXED',        500000,
     '등록 시',     'CARD',  2::SMALLINT, '임시값 — 등록비 규정 미수령'),
    ('BL-SP-01', 'LECTURE',      '단과 특강',     NULL,  'FIXED',        320000,
     '개강 3일 전', 'CARD',  3::SMALLINT, '임시값 — 특강 규정 미수령'),
    ('BL-SP-02', 'LECTURE',      '해설 특강',     NULL,  'FIXED',         90000,
     '개강 3일 전', 'CARD',  4::SMALLINT, '임시값 — 특강 규정 미수령'),
    ('BL-ML-01', 'MEAL',         '급식비 (1식)',  NULL,  'FIXED',          7700,
     '전월 말일',   'VBANK', 5::SMALLINT, '대구만 8,000원 — 지점 행으로 따로 둔다')
) AS v(code, item_type, name, round_name, amount_source, amount,
       due_desc, payment_method, sort_order, memo)
WHERE NOT EXISTS (
        SELECT 1 FROM billing_standard s
        WHERE s.academy_id IS NULL AND s.year = 2026 AND s.code = v.code
          AND s.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 대구 급식비 8,000원 — 지점 행이 공통본을 덮는다
-- ─────────────────────────────────────────────────────────────
INSERT INTO billing_standard (academy_id, year, code, item_type, name, round_name,
                              amount_source, amount, due_desc, payment_method,
                              active, sort_order, memo, created_by)
SELECT a.id, 2026, 'BL-ML-01', 'MEAL', '급식비 (1식)', NULL,
       'FIXED', 8000, '전월 말일', 'VBANK', TRUE, 5, '성림푸드', 0
FROM academy a
WHERE a.acad_nm = '대구'
  AND NOT EXISTS (
        SELECT 1 FROM billing_standard s
        WHERE s.academy_id = a.id AND s.year = 2026 AND s.code = 'BL-ML-01'
          AND s.is_deleted = FALSE);

-- ─────────────────────────────────────────────────────────────
-- 지난 기수 — 상태필터 확인용. 지우지 않고 내린 행이다
-- ─────────────────────────────────────────────────────────────
INSERT INTO billing_standard (academy_id, year, code, item_type, name, round_name,
                              amount_source, amount, due_desc, payment_method,
                              active, sort_order, memo, created_by)
SELECT NULL, 2026, 'BL-TU-99', 'TUITION', '2025 정규 교습비 (종료)', '3기',
       'FIXED', 1380000, '매월 25일', 'VBANK', FALSE, 99, '중지 상태 확인용', 0
WHERE NOT EXISTS (
        SELECT 1 FROM billing_standard s
        WHERE s.academy_id IS NULL AND s.year = 2026 AND s.code = 'BL-TU-99'
          AND s.is_deleted = FALSE);
