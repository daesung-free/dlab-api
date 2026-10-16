-- 청구에 「할인 근거」와 「대상 기간」을 더한다 (1016 피드백 2-5 · 2-6)
--
-- ★ 파일명이 14자리인 이유: 밑줄 방식(V20261016_1800)은 20261016.1800 으로 파싱돼
--   오늘 이미 적용된 V20261016110000(20261016110000) 보다 작다. 그러면 out-of-order 가
--   되고 운영은 out-of-order=false 라 기동이 막힌다.

-- 2-5 장학명 · 할인율 표기
--   ★ 발행 시점의 근거를 문자열로 박아 둔다. 장학(scholarship) 을 FK 로 걸지 않는 이유는
--     나중에 그 장학이 취소·변경되면 과거 청구의 근거가 함께 흔들리기 때문이다 —
--     상벌점이 부여 시점의 항목 점수를 복사해 두는 것과 같은 판단이다.
--   손으로 넣은 할인이면 비어 있다(= 장학 근거가 없다는 뜻).
ALTER TABLE billing ADD COLUMN discount_source VARCHAR(100);
COMMENT ON COLUMN billing.discount_source IS
    '할인 근거 — 발행 시점의 장학명 스냅샷(예: 수능 100%). 수기 할인이면 NULL';

-- 2-6 대상 기간
--   ⚠️ service_year / service_month 는 그대로 둔다. 환불 계산이 그 값으로 그 달
--     교습일수를 찾는다. 기간은 표시용으로 더하는 것이다.
--   ⚠️ 중도 입학 일할 건의 시작일은 「입학일」이고 달력으로 역산할 수 없다 —
--     remainingDays 가 교습일수 기준이라(설·추석 제외) 말일에서 빼면 어긋난다.
--     그래서 모르면 NULL 로 둔다. 0 이나 추측값을 넣으면 화면이 그것을 사실로 읽는다.
ALTER TABLE billing ADD COLUMN service_from DATE;
ALTER TABLE billing ADD COLUMN service_to   DATE;
COMMENT ON COLUMN billing.service_from IS '대상 기간 시작일. 월 전체면 그 달 1일, 일할이면 입학일. 모르면 NULL';
COMMENT ON COLUMN billing.service_to   IS '대상 기간 종료일. 보통 그 달 말일. 모르면 NULL';
