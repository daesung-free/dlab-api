-- V20260811_1700: 지점 설정 변경 이력 (F-4.10-7)
--
-- 시트가 "시크릿은 마스킹 표시 + 재발급 이력 감사로그 필수"를 요구한다.
--
-- ★ branch_config 자체로는 "누가 재발급했나"에 답할 수 없다.
--   created_by는 행을 처음 만든 사람이고(updatable=false), @LastModifiedBy 컬럼은
--   전 테이블에 없다. 지점 설정은 행이 지점당 1개뿐이라 계속 UPDATE만 되므로,
--   이력 테이블이 없으면 최초 생성자만 영원히 남는다.
--
-- ★ 값 자체는 남기지 않는다. 키오스크 시크릿·PG MID를 이력에 복사하면
--   비밀값이 테이블 두 곳으로 늘어나고, 폐기한 시크릿이 영구히 보존된다.
--   "언제 누가 무엇을 바꿨나"만 남기면 감사 목적은 달성된다.

CREATE TABLE branch_config_history (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,

    -- KIOSK_CREDENTIAL_ISSUED / PG_MERCHANT_CHANGED / NEBULA_DEVICE_CHANGED / POLICY_CHANGED
    action      VARCHAR(30) NOT NULL,
    -- 사람이 읽을 요약. "PG 가맹점코드 변경" 같은 한 줄
    detail      VARCHAR(200),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_branch_config_history_academy
    ON branch_config_history (academy_id, created_at DESC);

COMMENT ON TABLE branch_config_history IS
    '지점 설정 변경 감사로그(F-4.10-7). 값은 남기지 않는다 — 비밀값이 두 곳으로 늘어난다';

-- ★ branch_config에는 year를 넣지 않는다.
--   공통컬럼 규칙(§7)의 year는 "전년도 복사"의 기준이라 마스터성 테이블에 필요한 것인데,
--   지점 설정은 지점당 1행(UNIQUE academy_id)이고 연도가 바뀌어도 같은 값을 쓴다.
--   year를 넣으면 그 유니크 제약과 의미가 충돌한다 — 연도마다 시크릿이 갈리는 게 아니다.
