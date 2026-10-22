-- 홈페이지 입학신청 연동에 필요한 코드 (2026-10-22).
--
-- ★★ 운영 실측으로 둘 다 비어 있는 것을 확인했다. 그래서 자격증명을 넣어도
--   **모든 접수가 901「알 수 없는 학원코드」로 거부**된다.
--
--     academy.dlab_cd   11개 지점 전부 NULL
--     common_code       0 건
--
--   규격서는 시너지에 「분당 = F」를 주고 있는데 서버가 그 코드를 몰랐다.
--
-- ⚠️ 마이그레이션에 넣는 이유 — 운영에 손으로 SQL 을 치면 다음 지점이 생길 때
--   같은 일을 또 하고, 그때 빠뜨리면 그 지점만 조용히 접수가 안 된다.
--
-- ★ UPDATE 라 테스트에 영향이 없다. 테스트는 마이그레이션이 끝난 뒤 자기 지점을
--   만들므로 이 시점에 맞는 행이 없다(0건 갱신). 지점을 INSERT 하는 시드를
--   마이그레이션에 넣었다가 388건이 깨진 것과는 성질이 다르다.

-- ── 1. 입학신청 지점코드 (acid) ────────────────────────────
--
-- ★ 키오스크 acad_cd(31~48)와 규칙이 없는 별개 체계다. 분당이 31 이면서 F 이고
--   목동이 45 이면서 O 다 — 한쪽에서 다른 쪽을 유추할 수 없어 매핑을 들고 있어야 한다.
--   출처: DSA_DLab_연동_정의서 v1.2 (2026-01-30), 대성전산 부여.

UPDATE academy SET dlab_cd = 'F' WHERE acad_cd = '31' AND dlab_cd IS NULL;  -- 분당
UPDATE academy SET dlab_cd = 'I' WHERE acad_cd = '32' AND dlab_cd IS NULL;  -- 일산
UPDATE academy SET dlab_cd = 'T' WHERE acad_cd = '33' AND dlab_cd IS NULL;  -- 동탄
UPDATE academy SET dlab_cd = 'P' WHERE acad_cd = '34' AND dlab_cd IS NULL;  -- 김포
UPDATE academy SET dlab_cd = 'C' WHERE acad_cd = '42' AND dlab_cd IS NULL;  -- 부천
UPDATE academy SET dlab_cd = 'E' WHERE acad_cd = '43' AND dlab_cd IS NULL;  -- 이매
UPDATE academy SET dlab_cd = 'J' WHERE acad_cd = '44' AND dlab_cd IS NULL;  -- 광명
UPDATE academy SET dlab_cd = 'O' WHERE acad_cd = '45' AND dlab_cd IS NULL;  -- 목동
UPDATE academy SET dlab_cd = 'A' WHERE acad_cd = '46' AND dlab_cd IS NULL;  -- 송파

-- ⚠️ 대전(47)·대구(48)는 일부러 비워 둔다.
--
--   ★ 대성전산이 아직 코드를 부여하지 않았다. 개정 이력이 *"v1.2 — 송파점 학원코드
--     추가(ACID: 'A')"* 라 **지점이 생길 때마다 대성전산이 붙여주는 구조**다.
--   ⚠️ 값을 추측해서 넣지 말 것 — 어긋난 코드가 들어가면 「설정은 됐는데 접수가
--     엉뚱한 지점으로 들어가는」 상태가 되어 원인을 찾기가 훨씬 어려워진다.
--     비어 있으면 901 이 나서 바로 드러난다.
--   → 받으면 UPDATE 한 줄을 새 마이그레이션으로 추가한다.

-- ── 2. 과목 코드 (getSubInfo) ──────────────────────────────
--
-- ★ 지점 구분이 없다 — 규격서 3.6 이 acid 를 받지 않는다(academy_id = NULL).
-- ★ idx 가 과목 자리다. 성적 입력(setStdTest)의 subject 가 이 값을 쓴다.
--   ⚠️ 이게 비어 있으면 홈페이지 성적 입력 화면이 과목을 못 그린다.

-- ⚠️ year 컬럼이 없다 — 공통컬럼 규약(§7)의 예외다. 과목·선택항목은 연도와
--    무관한 참조 데이터라 그렇게 만들어져 있다. 넣으면 마이그레이션이 깨진다.
INSERT INTO common_code (grp, code, name, idx, academy_id, sort_order,
                         active, created_by, is_deleted)
SELECT 'SUBJECT', v.code, v.name, v.idx, NULL, v.idx, TRUE, 0, FALSE
FROM (VALUES
    ('1', '국어',   1::smallint),
    ('2', '수학',   2::smallint),
    ('3', '영어',   3::smallint),
    ('4', '탐구1',  4::smallint),
    ('5', '탐구2',  5::smallint)
) AS v(code, name, idx)
WHERE NOT EXISTS (
    SELECT 1 FROM common_code c
    WHERE c.grp = 'SUBJECT' AND c.code = v.code AND c.is_deleted = FALSE
);

-- ⚠️ 선택 항목 4종(ACAD 출신학원 · ADMI 지원기준 · EXAM 전형 · FIND 알게된경로)은
--   **값 목록을 디랩 본사에서 받지 못해 넣지 못한다.** 받으면 같은 방식으로 추가한다.
--   그때까지 getComInfo·/homepage/codes 는 빈 배열이고, 규격서에 그렇게 적어 뒀다.
