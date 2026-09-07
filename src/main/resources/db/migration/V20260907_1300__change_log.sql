-- V20260907_1300: 변경 이력(감사로그) — 첫 사용처는 계정 권한 변경
--
-- 지금은 **누가 누구 권한을 언제 바꿨는지 기록이 없다.** 바꾸면 그냥 덮어쓴다.
-- 사용자 관리 화면에 '권한 수정시간' 컬럼이 있는데 채울 데이터가 없는 상태였다.
--
-- ★ 왜 'account_role_history' 가 아니라 범용 표인가
--   요구사항정의서에 **F-4.10-8 변경 이력 조회(금일 수정 이력)**가 있고, 그 실현 방식이
--   "전용 화면이냐 공통 감사로그 뷰어냐"로 아직 미확정이다(오픈이슈 #50).
--   권한 전용으로 만들면 공통으로 갈 때 표가 두 벌이 되고 **이미 쌓인 이력을 옮겨야 한다.**
--   범용으로 두면 그 화면이 이 표를 그대로 읽는다 — #50 답을 기다리지 않아도 된다.
--
-- ★ 그리고 감사로그는 늦을수록 비싸다.
--   나중에 붙여도 **안 남긴 기간은 복구할 수 없다.** "누가 이 계정에 SUPER_ADMIN 을
--   줬나"에 영영 답할 수 없게 된다. 보안심사 직결 항목이다.
--
-- ★ before_value / after_value 는 사람이 읽는 요약이다.
--   구조화된 diff 를 넣으면 대상마다 형태가 달라져 공통 뷰어가 못 그린다.
--   "SUPER_ADMIN, STAFF" 처럼 화면에 그대로 찍을 수 있는 문자열로 둔다.

CREATE TABLE change_log (
    id           BIGSERIAL   PRIMARY KEY,
    -- 지점·연도는 공통 컬럼 규약이지만 여기서는 NULL 을 허용한다 —
    -- 계정처럼 지점이 없는 대상이 있고, 없는 지점을 지어내면 조회가 더 틀어진다.
    --
    -- ★ FK 를 걸지 않는다. 두 가지 이유가 겹친다.
    --   ① 감사 로그는 **대상이 지워져도 남아야 한다.** 지점이 삭제됐다고 그 지점에서
    --      누가 무슨 권한을 받았는지가 같이 사라지면 로그의 의미가 없다.
    --      target_id 에 FK 가 없는 것과 같은 이유다.
    --   ② 적재가 REQUIRES_NEW 로 분리돼 있다(본 작업이 실패해도 이력은 남기려고).
    --      그러면 **같은 요청에서 방금 만든 지점을 새 트랜잭션이 아직 못 본다** —
    --      FK 가 있으면 그 순간 제약 위반으로 터진다. 실제로 테스트에서 터졌다.
    academy_id   BIGINT,
    year         SMALLINT,
    -- 무엇에 대한 변경인가. 'ACCOUNT_ROLE' 처럼 대상 종류를 문자열로 둔다 —
    -- enum 으로 박으면 대상이 늘 때마다 마이그레이션을 새로 써야 한다.
    target_type  VARCHAR(40) NOT NULL,
    target_id    BIGINT      NOT NULL,
    -- 사람이 읽는 대상 이름(계정 로그인 아이디 등). 대상이 지워져도 이력이 읽혀야 한다.
    target_label VARCHAR(100),
    action       VARCHAR(40) NOT NULL,
    before_value TEXT,
    after_value  TEXT,
    memo         VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 화면이 "이 계정의 이력"과 "오늘 전체 변경"을 둘 다 본다.
CREATE INDEX idx_change_log_target  ON change_log (target_type, target_id, created_at DESC);
CREATE INDEX idx_change_log_created ON change_log (created_at DESC);

COMMENT ON TABLE  change_log IS 'F-4.10-8 변경 이력. 대상 종류를 문자열로 둬 확장한다';
COMMENT ON COLUMN change_log.created_by IS '행위자 계정 id. 배치·시스템 경로는 0';
