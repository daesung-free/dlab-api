-- V20260807_1600: 9개 지점 시드
--
-- ★ 지점을 만드는 API를 두지 않는다. 지점은 9개로 고정이고 acad_cd는 대성전산이
--   부여한 값이라 우리가 새로 만들 일이 없다(요구사항정의서에도 등록 화면이 없다).
--   그래서 최초 심기를 마이그레이션으로 한다.
--
-- ★ acad_cd는 연속이 아니다 — 31~34 다음이 42로 건너뛴다.
--   대성전산이 부여한 임의값이고 키오스크 stores 테이블에 이미 박혀 있어서
--   바꾸면 그쪽이 깨진다. 규칙을 찾으려 하지 말고 이 값을 그대로 승계한다.
--
-- ★ store_code는 키오스크 stores.store_code와 1:1로 맞춘다.
--   (그쪽 R__seed_dsa_credentials.sql과 대조해 일치 확인함)
--   어긋나면 그 지점만 조용히 연동이 끊긴다.
--
-- 등원 기준 시각은 09:00을 기본으로 넣는다 — 지각 판정 기준이라 지점별 실제 값을
-- 확인해 화면에서 고쳐야 한다.

INSERT INTO academy (acad_cd, acad_nm, full_nm, store_code, attendance_deadline, active)
SELECT v.cd, v.nm, v.full_nm, v.store_cd, '09:00'::time, TRUE
FROM (VALUES
    ('31', '분당', 'D.Lab 분당', 'DS-001'),
    ('32', '일산', 'D.Lab 일산', 'DS-002'),
    ('33', '동탄', 'D.Lab 동탄', 'DS-003'),
    ('34', '김포', 'D.Lab 김포', 'DS-004'),
    ('42', '부천', 'D.Lab 부천', 'DS-005'),
    ('43', '이매', 'D.Lab 이매', 'DS-006'),
    ('44', '광명', 'D.Lab 광명', 'DS-007'),
    ('45', '목동', 'D.Lab 목동', 'DS-008'),
    ('46', '송파', 'D.Lab 송파', 'DS-009')
) AS v(cd, nm, full_nm, store_cd)
-- 이미 있으면 건너뛴다. 개발 중 손으로 넣어둔 지점을 덮어쓰지 않는다
WHERE NOT EXISTS (SELECT 1 FROM academy a WHERE a.acad_cd = v.cd);

COMMENT ON COLUMN academy.acad_cd IS
    '대성전산 부여 지점코드. 연속이 아니다(31~34 다음 42) — 키오스크가 이 값으로 인증한다';
COMMENT ON COLUMN academy.store_code IS
    '키오스크 stores.store_code와 1:1. 어긋나면 그 지점 연동이 조용히 끊긴다';
