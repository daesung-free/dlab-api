-- 입학신청 — 디랩이 못 주던 코드를 전부 줬다 (2026-10-22 회신).
--
-- V20261022_1200 에서 「아직 못 받았다」로 비워 둔 두 자리를 채운다.
--
--     대전·대구 지점코드      ← 「대성전산이 부여하는 구조」라 비워 뒀던 것
--     선택 항목 4종 24건      ← 9/29 부터 「미수령」으로 들고 있던 것
--
-- ★ 그 전까지 대전·대구로 보낸 접수는 901「알 수 없는 학원코드」로 거부됐고,
--   getComInfo·/homepage/codes 는 빈 배열이었다. 둘 다 이 마이그레이션으로 풀린다.
--
-- ⚠️ 규격서 2판(1022)이 *"대전·대구는 코드가 아직 없으니 홈페이지 지점 선택에서
--   빼 두십시오"* 라고 적어 보냈다. **이 마이그레이션이 그 문장을 낡게 만든다** —
--   배포와 함께 규격서 §1.2 를 고쳐 다시 보내야 한다.

-- ── 1. 대전 R · 대구 U ─────────────────────────────────────
--
-- ★ 대성전산이 아니라 디랩이 줬다. 연동 정의서 v1.2(2026-01-30)에는 9개뿐이고
--   거기에 개정으로 붙는 구조라, 이 두 값은 정의서 개정판에서 다시 대조할 것.
-- ⚠️ uq_academy_dlab_cd 가 걸려 있다. R·U 는 기존 9개와 겹치지 않는다.
-- ★ dlab_cd IS NULL 조건을 유지한다 — 이미 값이 있으면 건드리지 않는다.

UPDATE academy SET dlab_cd = 'R' WHERE acad_cd = '47' AND dlab_cd IS NULL;  -- 대전
UPDATE academy SET dlab_cd = 'U' WHERE acad_cd = '48' AND dlab_cd IS NULL;  -- 대구

-- ── 2. 선택 항목 4종 24건 (getComInfo · /homepage/codes) ───
--
-- ★ code 가 VARCHAR(20) 이다 — 숫자로 다루지 말 것. 선행 0 이 있는 코드가 와도
--   그대로 보관돼야 하고, 홈페이지가 이 문자열을 그대로 돌려보낸다.
-- ★ academy_id = NULL 로 넣는다. 조회가 「공통 + 그 지점」을 합쳐서 주므로
--   나중에 지점 전용 항목이 생기면 그 지점 행만 더하면 되고 조회는 안 고친다.
-- ⚠️ year 컬럼이 없다(V20261022_1200 주석 참고). 넣으면 깨진다.
-- ★ 디랩이 「새롭게 재정의하셔도 무방」이라고 했으나 받은 값을 그대로 쓴다 —
--   홈페이지가 이미 이 코드로 붙어 있을 수 있고, 바꾸면 양쪽을 동시에 고쳐야 한다.
-- ★ sort_order 는 디랩이 적어 준 순서다. 코드 번호순이 아니다(1000 기숙학원이
--   647 그랜램프 뒤에 온다) — 화면에 뜨는 순서라 번호로 정렬하지 말 것.

INSERT INTO common_code (grp, code, name, academy_id, sort_order,
                         active, created_by, is_deleted)
SELECT v.grp, v.code, v.name, NULL, v.sort_order, TRUE, 0, FALSE
FROM (VALUES
    -- 출신학원
    ('ACAD', '637',  '잇올',            1::smallint),
    ('ACAD', '645',  '러셀',            2::smallint),
    ('ACAD', '646',  '이투스',          3::smallint),
    ('ACAD', '647',  '그랜램프',        4::smallint),
    ('ACAD', '648',  '재수종합학원',    5::smallint),
    ('ACAD', '1000', '기숙학원',        6::smallint),
    ('ACAD', '649',  '기타',            7::smallint),
    ('ACAD', '1001', '타독서실',        8::smallint),
    ('ACAD', '1002', '출신학원 없음',   9::smallint),
    -- 지원기준
    ('ADMI', '1007', '수능',            1::smallint),
    ('ADMI', '1004', '6월평가원',       2::smallint),
    ('ADMI', '1009', '9월평가원',       3::smallint),
    ('ADMI', '1191', '모의고사',        4::smallint),
    ('ADMI', '1006', '대학교 재학중',   5::smallint),
    ('ADMI', '1010', '내신',            6::smallint),
    ('ADMI', '1005', '면접 전형',       7::smallint),
    -- 전형
    ('EXAM', '1229', '재학생',          1::smallint),
    ('EXAM', '1230', '정규시즌',        2::smallint),
    -- 알게된 경로
    ('FIND', '636',  '대성학원홈페이지', 1::smallint),
    ('FIND', '640',  '포털사이트검색',   2::smallint),
    ('FIND', '641',  '블로그 및 커뮤니티', 3::smallint),
    ('FIND', '642',  '언론보도',        4::smallint),
    ('FIND', '643',  '지인추천',        5::smallint),
    ('FIND', '644',  '기타',            6::smallint)
) AS v(grp, code, name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM common_code c
    WHERE c.grp = v.grp AND c.code = v.code
      AND c.academy_id IS NULL AND c.is_deleted = FALSE
);
