-- ==========================================================================
-- 검색조건 저장 (실행가이드 P1-01 "통합검색·정렬·조건저장")
--
-- 학생 검색이 12개 조건이라, 자주 쓰는 조합을 매번 다시 입력하는 게 실무 부담이다.
-- "내 반 재원생", "미납자" 같은 조합을 이름 붙여 저장해두고 불러 쓴다.
-- ==========================================================================

CREATE TABLE saved_search (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    -- 저장한 사람. 개인 설정이라 계정 단위다 — 공유 조건이 필요해지면 그때 컬럼을 더한다.
    account_id  BIGINT      NOT NULL REFERENCES account (id),
    -- 어느 화면의 조건인가. 지금은 학생 검색뿐이지만 명단·수납 등으로 늘어난다.
    search_type VARCHAR(30) NOT NULL,
    name        VARCHAR(50) NOT NULL,
    -- 조건 원본(JSON 문자열).
    -- ★ 컬럼으로 펼치지 않는다 — 조건이 화면마다 다르고 12개가 더 늘 수 있는데,
    --   펼치면 조건 하나 추가할 때마다 마이그레이션이 필요해진다.
    --   그리고 이 값으로 검색하는 게 아니라 "화면에 그대로 되돌려주는" 용도라 파싱할 일이 없다.
    conditions  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 같은 사람이 같은 화면에 같은 이름을 두 번 저장하면 어느 게 최신인지 알 수 없다
    CONSTRAINT uq_saved_search UNIQUE (account_id, search_type, name)
);

-- year 없음 — 검색 조건 자체는 연도에 종속되지 않는다(의도된 예외).
--   조회 연도는 조건 JSON 안에 들어가고, 전년도 복사 대상도 아니다.

COMMENT ON TABLE saved_search IS '자주 쓰는 검색조건 저장. 계정별 개인 설정이다.';
COMMENT ON COLUMN saved_search.conditions IS
    '조건 JSON 원본. 화면에 되돌려주는 용도라 서버가 파싱하지 않는다 — 조건이 늘어도 스키마 변경이 없다.';

CREATE INDEX idx_saved_search_account ON saved_search (account_id, search_type);
