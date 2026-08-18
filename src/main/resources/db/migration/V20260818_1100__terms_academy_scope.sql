-- V20260818_1100: 약관 지점 범위 (장학 동의서 수령분 반영)
--
-- 클라이언트가 준 장학 동의서 3종(정규시즌·반수시즌·환급반)에 문구가 `DLab [지점]`으로
-- 되어 있고, 환급반은 아예 `[광명]`으로 박혀 있다. 회신에도
-- *"장학종류별, 지점별로 동의서 내용이 상이할 수 있습니다"*로 적혀 왔다.
--
-- ★ 장학 "종류"별은 컬럼이 필요 없다 — `code`가 자유 문자열이라
--   SCHOLARSHIP_REGULAR / SCHOLARSHIP_BANSU / REFUND_CLASS로 나누면 된다.
--   종류를 컬럼으로 두면 약관 종류가 늘 때마다 enum·마이그레이션이 따라온다.
--
-- ★ 지점만 컬럼이 필요하다. 같은 code·version이 지점마다 다른 문구를 가질 수 있어서다.
--   공휴일(holiday.academy_id)과 같은 방식 — NULL이면 전 지점 공통, 값이 있으면 그 지점.
--   조회는 둘을 합쳐 보되 지점 것이 공통을 이긴다.

ALTER TABLE terms ADD COLUMN academy_id BIGINT REFERENCES academy (id);

COMMENT ON COLUMN terms.academy_id IS
    'NULL이면 전 지점 공통. 값이 있으면 그 지점 전용이며 같은 code의 공통본을 대체한다';

-- ─────────────────────────────────────────────────────────────
-- 유니크 재구성
--
-- ★ 기존 UNIQUE (code, version)을 그대로 두면 지점별 문구를 넣을 수 없다.
--   그렇다고 (academy_id, code, version)으로 바꾸면 PostgreSQL이 NULL을 서로 다른
--   값으로 보기 때문에 **공통 약관이 같은 버전으로 여러 벌 들어간다.**
--   그래서 공통분과 지점분을 부분 인덱스 둘로 나눠 건다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE terms DROP CONSTRAINT IF EXISTS uq_terms;

CREATE UNIQUE INDEX uq_terms_common
    ON terms (code, version) WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_terms_academy
    ON terms (academy_id, code, version) WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

-- 조회가 (지점, 코드, 시행일)로 들어간다
CREATE INDEX idx_terms_academy_code
    ON terms (academy_id, code, effective_at DESC) WHERE is_deleted = FALSE;
