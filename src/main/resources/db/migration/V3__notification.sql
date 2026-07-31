-- V3: 알림 도메인 (이벤트-채널 매핑 + 템플릿 + 발송 로그)
-- 참고: CLAUDE.md §4 — 알림 "문구"는 아직 미확정 블로커다.
--   그래서 문구는 확정하지 않고 구조(이벤트 → 채널 매핑, 변수 슬롯)만 먼저 만든다.
--   content_confirmed = FALSE 인 템플릿은 "문구 미확정" 상태를 뜻한다.
-- 참고: CLAUDE.md §3 — 모든 학생 관련 템플릿은 {studentName} 변수를 반드시 포함해야 한다.

CREATE TABLE notification_template (
    id                 BIGSERIAL    PRIMARY KEY,
    -- 도메인 이벤트 코드 (com.dlab.domain.notification.NotificationEvent 와 1:1)
    event_code         VARCHAR(60)  NOT NULL UNIQUE,
    -- KAKAO_ALIMTALK(중요 알림) / FCM_PUSH(일반 알림)
    channel            VARCHAR(20)  NOT NULL,
    title_template     VARCHAR(200) NOT NULL DEFAULT '',
    body_template      TEXT         NOT NULL DEFAULT '',
    -- 이 템플릿이 반드시 채워야 하는 변수명 목록. 렌더링 시 누락되면 발송하지 않고 실패시킨다.
    required_variables TEXT         NOT NULL DEFAULT 'studentName',
    -- 카카오 알림톡은 사전심사가 필요하므로 심사 통과한 템플릿 코드를 여기 보관
    kakao_template_code VARCHAR(60),
    -- 문구 확정 여부. FALSE면 아직 운영팀과 문구 협의 전이라는 뜻(§4 블로커).
    content_confirmed  BOOLEAN      NOT NULL DEFAULT FALSE,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notification_log (
    id                   BIGSERIAL    PRIMARY KEY,
    branch_id            BIGINT       REFERENCES branch (id),
    event_code           VARCHAR(60)  NOT NULL,
    channel              VARCHAR(20)  NOT NULL,
    recipient_account_id BIGINT       NOT NULL REFERENCES user_account (id),
    -- 어떤 학생에 대한 알림인지. 다자녀 학부모 구분에 필요.
    student_id           BIGINT       REFERENCES student (id),
    title                VARCHAR(200) NOT NULL DEFAULT '',
    body                 TEXT         NOT NULL DEFAULT '',
    -- 렌더링에 사용한 변수 원본 (재발송·디버깅용)
    variables            JSONB,
    -- PENDING / SENT / FAILED / SKIPPED
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    fail_reason          VARCHAR(500),
    sent_at              TIMESTAMPTZ,
    -- 중복 발송 방지 키. 예: 미등원 알림은 "MISSING_ATTENDANCE:{studentId}:{날짜}".
    -- 배치가 여러 번 돌아도 같은 알림이 두 번 나가지 않도록 보장한다.
    dedup_key            VARCHAR(200),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_notification_log_dedup ON notification_log (dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX idx_notification_log_recipient ON notification_log (recipient_account_id, created_at DESC);
CREATE INDEX idx_notification_log_event ON notification_log (event_code, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- 이벤트 → 채널 매핑표 (문구는 미확정이라 비워둔다)
-- 채널 기준: 놓치면 안 되는 알림(미등원, 방화벽 승인, 미납)은 카카오 알림톡, 나머지는 FCM Push.
-- ─────────────────────────────────────────────────────────────
INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    ('MISSING_ATTENDANCE',              'KAKAO_ALIMTALK', 'studentName,attendanceDate'),
    ('FIREWALL_REQUEST_CREATED',        'KAKAO_ALIMTALK', 'studentName,requestedAt,timeoutMinutes'),
    ('FIREWALL_APPROVED_BY_PARENT',     'FCM_PUSH',       'studentName,resolvedAt'),
    ('FIREWALL_APPROVED_AFTER_TIMEOUT', 'KAKAO_ALIMTALK', 'studentName,resolvedAt,timeoutMinutes'),
    ('FIREWALL_APPROVED_BEFORE_TIMEOUT','KAKAO_ALIMTALK', 'studentName,resolvedAt'),
    ('FIREWALL_REJECTED',               'FCM_PUSH',       'studentName,resolvedAt');
