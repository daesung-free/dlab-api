-- 장학 종류 마스터 시드 (개발·화면 확인용)
--
-- ★ 마이그레이션이 아니라 여기 있는 이유
--   장학 종류·할인율은 지점·연도마다 다르고 확정 자료를 못 받았다. 마이그레이션에
--   임의값을 심으면 그 값으로 장학이 부여된 뒤 취소 규칙과 어긋난다 —
--   penalty_rule·exam_form 과 같은 방식이다.
--
-- ⚠️ 아래 코드는 0826 답변서 표기를 옮긴 것이고 **지점이 실제로 쓰는 값과 대조되지
--    않았다.** 운영 컷오버 때 실제 값으로 등록해야 한다.
--
-- ★ code 가 scholarship_cancel_rule.scholarship_type 과 정확히 같아야 한다.
--   V20260830_1000 의 규칙 시드(CSAT_100 / CSAT_50 / KICE_50 / KICE_30)와 맞춰 둔다 —
--   한 글자만 달라도 그 장학 학생이 취소 판정에서 통째로 빠진다.

INSERT INTO scholarship_master (academy_id, year, code, name, discount_rate,
                                active, sort_order, memo, created_by)
SELECT NULL, 2026, v.code, v.name, v.rate, TRUE, v.sort_order, v.memo, 0
FROM (VALUES
    ('CSAT_100', '수능 100%',   100.00, 1::SMALLINT, '수능 기준 · 전액'),
    ('CSAT_50',  '수능 50%',     50.00, 2::SMALLINT, '수능 기준'),
    ('KICE_50',  '평가원 50%',   50.00, 3::SMALLINT, '6·9월 평가원 기준 · 영어 2등급 이내'),
    ('KICE_30',  '평가원 30%',   30.00, 4::SMALLINT, '6·9월 평가원 기준 · 영어 2등급 이내'),
    ('NASIN_50', '내신 50%',     50.00, 5::SMALLINT, '취소 규칙 미등록')
) AS v(code, name, rate, sort_order, memo)
WHERE NOT EXISTS (
        SELECT 1 FROM scholarship_master m
        WHERE m.academy_id IS NULL AND m.year = 2026 AND m.code = v.code
          AND m.is_deleted = FALSE);
