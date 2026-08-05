-- ==========================================================================
-- 앱 설정·초기화 (앱 A-21 · A-C3 / 관리자 F-4.12-2 · F-4.12-3)
--
--   ① app_config      앱 부팅 시 첫 호출. 최소 지원 버전 · 점검 모드
--   ② terms           약관 (버전별로 행이 쌓인다)
--   ③ term_agreement  동의 이력. "그때 어떤 문구에 동의했나"에 답해야 한다
--   ④ notification_preference  알림 수신 설정
--   ⑤ push_token      FCM 토큰
--
-- ⚠️ 공통컬럼 중 academy_id·year가 없다(의도된 예외). 앱 버전·약관·수신 설정은
--    지점이나 학년도에 종속되지 않는다 — 지점마다 최소 지원 버전이 다르면
--    앱을 지점별로 빌드해야 한다.
-- ==========================================================================


-- ==========================================================================
-- ① 앱 설정 (F-4.12-3)
-- ==========================================================================

-- 시트가 데이터 항목을 `app_configs`로 지정했다.
-- ★ 홈 배너는 여기 두지 않는다 — 같은 칸이 `notices`도 함께 적어놨고,
--   배너는 노출 기간·대상·본문이 붙어 공지와 같은 물건이다.
CREATE TABLE app_config (
    id                  BIGSERIAL   PRIMARY KEY,
    -- 스토어가 갈라져 있어 버전이 서로 다르게 올라간다. 한 행으로 묶으면
    -- iOS 심사가 밀릴 때 안드로이드까지 같이 막힌다.
    platform            VARCHAR(10) NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    -- 이 버전 미만이면 강제 업데이트. 문자열로 두고 서버가 의미 비교한다
    -- (`1.10.0` < `1.9.0`이 되는 사전순 비교를 쓰면 안 된다 — AppVersion 참고).
    min_version         VARCHAR(20) NOT NULL,
    -- 권장 버전. 강제는 아니고 "업데이트 있음" 안내용
    latest_version      VARCHAR(20),
    maintenance         BOOLEAN     NOT NULL DEFAULT FALSE,
    maintenance_message VARCHAR(300),
    -- 종료 예정 시각. 있으면 앱이 "○시까지"를 보여줄 수 있다
    maintenance_until   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 플랫폼당 1행. soft delete를 쓰므로 살아 있는 행만 묶는다.
CREATE UNIQUE INDEX uq_app_config_platform ON app_config (platform) WHERE is_deleted = FALSE;

COMMENT ON TABLE app_config IS
    '앱 부팅 시 조회하는 설정. 앱에 박아두면 못 바꾼다 — 점검한다고 앱을 재심사받을 수는 없다.';
COMMENT ON COLUMN app_config.min_version IS
    '이 버전 미만이면 강제 업데이트. 사전순이 아니라 의미 비교다(1.10.0 > 1.9.0).';

-- 기동 직후 앱이 호출해도 404가 나지 않도록 기본값을 넣어둔다.
-- 점검 아님 + 최소 버전 1.0.0이라 아무 앱도 막지 않는다.
INSERT INTO app_config (platform, min_version, latest_version, maintenance) VALUES
    ('IOS',     '1.0.0', '1.0.0', FALSE),
    ('ANDROID', '1.0.0', '1.0.0', FALSE);


-- ==========================================================================
-- ② 약관
-- ==========================================================================

-- ★ 약관은 수정하지 않고 새 버전을 추가한다.
--   문구를 덮어쓰면 이미 동의한 사람이 "무엇에 동의했는지"가 사라진다.
--   동의는 법적 성격이라 그 기록이 곧 근거다.
CREATE TABLE terms (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL,
    version      VARCHAR(20)  NOT NULL,
    title        VARCHAR(100) NOT NULL,
    content      TEXT         NOT NULL,
    -- 필수 약관은 동의 없이 가입이 진행되지 않는다. 선택(마케팅 등)은 건너뛸 수 있다.
    required     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 시행일. 미래로 두면 예약 등록이 된다 — 앱은 시행된 것만 받는다.
    effective_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_terms UNIQUE (code, version)
);

COMMENT ON TABLE terms IS '약관. 문구를 고치지 말고 버전을 올려 행을 추가한다.';
COMMENT ON COLUMN terms.code IS 'SERVICE 이용약관 / PRIVACY 개인정보 수집·이용 / MARKETING 광고성 정보 수신 등';

CREATE INDEX idx_terms_code ON terms (code, effective_at DESC) WHERE is_deleted = FALSE;


-- ★ 동의 이력은 덮어쓰지 않고 쌓는다(append-only).
--   철회도 행으로 남겨야 "언제 동의했다가 언제 철회했나"에 답할 수 있다.
--   현재 상태는 가장 최근 행이다.
CREATE TABLE term_agreement (
    id         BIGSERIAL   PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES account (id),
    -- ★ code가 아니라 terms 행을 가리킨다 — 그래야 "그때 그 문구"가 특정된다.
    --   code만 저장하면 약관이 개정된 뒤 무엇에 동의했는지 알 수 없다.
    terms_id   BIGINT      NOT NULL REFERENCES terms (id),
    agreed     BOOLEAN     NOT NULL,
    agreed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE term_agreement IS
    '동의 이력(append-only). 철회도 행으로 남긴다 — 현재 상태는 가장 최근 행이다.';

CREATE INDEX idx_term_agreement_account ON term_agreement (account_id, terms_id, agreed_at DESC);


-- ==========================================================================
-- ③ 알림 수신 설정 (A-21)
-- ==========================================================================

-- ★ 행이 없으면 "수신"이다(opt-out).
--   가입 시 전 이벤트 행을 만들어두는 방식은, 이벤트가 추가될 때마다
--   기존 계정 전체에 백필이 필요해진다 — 빠뜨리면 새 알림이 아무에게도 안 간다.
CREATE TABLE notification_preference (
    id         BIGSERIAL   PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES account (id),
    event_code VARCHAR(50) NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_notification_preference UNIQUE (account_id, event_code)
);

COMMENT ON TABLE notification_preference IS
    '알림 수신 설정. 행이 없으면 수신(opt-out)이다. 필수 알림은 애플리케이션에서 끄지 못하게 막는다.';


-- ==========================================================================
-- ④ FCM 토큰 (A-21 · A-C3 · F-4.12-2)
-- ==========================================================================

-- ★ 토큰은 계정이 아니라 <b>기기</b>에 붙는다.
--   같은 기기에서 로그아웃하고 다른 계정으로 로그인하면 같은 토큰이 다른 계정으로 올라온다.
--   토큰을 계정별로만 쌓으면 이전 사용자에게 계속 알림이 간다 — 남의 자녀 출결이 뜬다.
--   그래서 토큰을 유니크로 잡고, 재등록 시 소유 계정을 갈아끼운다.
CREATE TABLE push_token (
    id           BIGSERIAL    PRIMARY KEY,
    account_id   BIGINT       NOT NULL REFERENCES account (id),
    token        VARCHAR(255) NOT NULL,
    platform     VARCHAR(10)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    -- 마지막으로 앱이 이 토큰을 확인해준 시각. 오래된 토큰 정리(F-4.12-2 "만료 단말 목록")에 쓴다.
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_push_token ON push_token (token) WHERE is_deleted = FALSE;
CREATE INDEX idx_push_token_account ON push_token (account_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE push_token IS
    'FCM 토큰. 한 계정에 기기 여러 대가 붙을 수 있고, 한 토큰은 한 계정에만 붙는다.';
COMMENT ON COLUMN push_token.token IS
    '기기 식별자. 재설치·갱신으로 계속 바뀐다 — 옛 토큰으로 보내면 실패하므로 갱신될 때마다 덮어쓴다.';
