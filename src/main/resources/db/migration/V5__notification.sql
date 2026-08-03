-- V5: 알림 (이벤트-채널 매핑 + 템플릿 + 발송 로그)
--
-- 설계 근거는 docs/entity-design.md K.
-- 알림 "문구"는 미확정 블로커(I-4)다. 문구는 확정하지 않고 구조(이벤트 → 채널 매핑,
-- 변수 슬롯)만 만든다. content_confirmed = FALSE인 템플릿은 실제 발송하지 않는다.

CREATE TABLE notification_template (
    id                  BIGSERIAL    PRIMARY KEY,
    event_code          VARCHAR(60)  NOT NULL UNIQUE,
    -- 놓치면 안 되는 알림은 카카오 알림톡, 일반 알림은 FCM Push
    channel             VARCHAR(20)  NOT NULL CHECK (channel IN ('KAKAO_ALIMTALK', 'FCM_PUSH')),
    title_template      VARCHAR(200) NOT NULL DEFAULT '',
    body_template       TEXT         NOT NULL DEFAULT '',
    -- 렌더링 시 반드시 채워져야 하는 변수. 누락되면 발송하지 않고 실패시킨다.
    required_variables  TEXT         NOT NULL DEFAULT 'studentName',
    -- 카카오 알림톡은 사전심사(E-5)가 필요하므로 승인된 템플릿 코드를 보관
    kakao_template_code VARCHAR(60),
    -- FALSE면 아직 운영팀과 문구 협의 전이라는 뜻
    content_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 템플릿은 전 지점 공통 참조값이다(의도된 예외).

CREATE TABLE notification_log (
    id                   BIGSERIAL    PRIMARY KEY,
    academy_id           BIGINT       REFERENCES academy (id),
    year                 SMALLINT,
    event_code           VARCHAR(60)  NOT NULL,
    channel              VARCHAR(20)  NOT NULL,
    recipient_account_id BIGINT       NOT NULL REFERENCES account (id),
    -- 어떤 학생에 대한 알림인지. 다자녀 학부모 구분에 필요.
    -- 사람(student)을 가리킨다 — 알림은 "누구 얘기인지"가 중요하지 기수가 중요하지 않다.
    student_id           BIGINT       REFERENCES student (id),
    title                VARCHAR(200) NOT NULL DEFAULT '',
    body                 TEXT         NOT NULL DEFAULT '',
    variables            JSONB,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    fail_reason          VARCHAR(500),
    sent_at              TIMESTAMPTZ,
    -- 중복 발송 방지 키. 예: 미등원 알림은 'MISSING_ATTENDANCE:{studentId}:{날짜}:{수신자}'.
    -- 배치가 재실행되거나 인스턴스가 여러 대여도 같은 알림이 두 번 나가지 않는다.
    dedup_key            VARCHAR(200),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_notification_dedup ON notification_log (dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX idx_notification_recipient ON notification_log (recipient_account_id, created_at DESC);
CREATE INDEX idx_notification_event ON notification_log (event_code, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- 이벤트 → 채널 매핑 (문구는 미확정이라 비워둔다)
-- 방화벽 승인 3케이스가 각각 다른 이벤트인 이유: 학부모에게 나가는 문구가 달라야 하기 때문.
-- ─────────────────────────────────────────────────────────────
INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    ('MISSING_ATTENDANCE',               'KAKAO_ALIMTALK', 'studentName,attendanceDate'),
    ('APPROVAL_REQUEST_CREATED',         'KAKAO_ALIMTALK', 'studentName,requestedAt,timeoutMinutes'),
    ('APPROVAL_APPROVED_BY_PARENT',      'FCM_PUSH',       'studentName,resolvedAt'),
    ('APPROVAL_APPROVED_AFTER_TIMEOUT',  'KAKAO_ALIMTALK', 'studentName,resolvedAt,timeoutMinutes'),
    ('APPROVAL_APPROVED_BEFORE_TIMEOUT', 'KAKAO_ALIMTALK', 'studentName,resolvedAt'),
    ('APPROVAL_REJECTED',                'FCM_PUSH',       'studentName,resolvedAt');
