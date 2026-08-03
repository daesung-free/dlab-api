-- V4: 방화벽 해제 + 상벌점
--
-- 설계 근거는 docs/entity-design.md E', V.

-- ─────────────────────────────────────────────────────────────
-- 방화벽 해제 신청 — 승인 로직은 갖지 않는다. approval_request에 위임하고
-- 여기는 해제 자체의 고유 정보(시간·Zyxel)만 담는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE firewall_request (
    id                  BIGSERIAL   PRIMARY KEY,
    academy_id          BIGINT      NOT NULL REFERENCES academy (id),
    year                SMALLINT    NOT NULL,
    enrollment_id       BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 승인 상태·케이스는 전부 여기서 읽는다
    approval_request_id BIGINT      NOT NULL REFERENCES approval_request (id),
    requested_minutes   SMALLINT    NOT NULL CHECK (requested_minutes > 0 AND requested_minutes <= 300),
    reason              VARCHAR(500),
    unlock_start_at     TIMESTAMPTZ,
    unlock_end_at       TIMESTAMPTZ,
    -- Nebula API 호출 대상. ⚠️ 크레덴셜·제어 단위는 E-1 미해결.
    zyxel_site_id       VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_firewall_approval ON firewall_request (approval_request_id);
CREATE INDEX idx_firewall_enrollment ON firewall_request (enrollment_id);

COMMENT ON COLUMN firewall_request.requested_minutes IS '최대 300분(5시간).';

-- ─────────────────────────────────────────────────────────────
-- 상벌점 — ★ attendance 하위에 두지 않는다.
-- 규칙엔진은 출결과 데일리루틴 양쪽에서 트리거되므로(I-5),
-- attendance 아래 두면 daily_routine → attendance 순환 참조가 난다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE penalty_item (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,
    item_name   VARCHAR(100) NOT NULL,
    point_value INT         NOT NULL,
    category    VARCHAR(10) NOT NULL CHECK (category IN ('MERIT', 'DEMERIT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_penalty_item UNIQUE (academy_id, year, item_name)
);

COMMENT ON TABLE penalty_item IS '항목 마스터. 전년도 복사 대상 — 복사 의존순서는 docs/entity-design.md §0-2.';

CREATE TABLE penalty_rule (
    id                BIGSERIAL   PRIMARY KEY,
    academy_id        BIGINT      NOT NULL REFERENCES academy (id),
    year              SMALLINT    NOT NULL,
    trigger_type      VARCHAR(20) NOT NULL CHECK (trigger_type IN ('ATTENDANCE', 'DAILY_ROUTINE')),
    -- 트리거 조건. ⚠️ 매핑 규칙 자체가 미확정(I-5)이라 스키마만 두고 값은 비워둔다.
    trigger_condition VARCHAR(100) NOT NULL,
    penalty_item_id   BIGINT      NOT NULL REFERENCES penalty_item (id),
    active            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON COLUMN penalty_rule.active IS
    '기본 FALSE — 규칙 매핑(I-5)이 확정되기 전엔 자동 부여를 켜지 않는다. 수기 부여만 먼저 연다.';

CREATE TABLE penalty_point (
    id              BIGSERIAL    PRIMARY KEY,
    academy_id      BIGINT       NOT NULL REFERENCES academy (id),
    year            SMALLINT     NOT NULL,
    enrollment_id   BIGINT       NOT NULL REFERENCES student_enrollment (id),
    penalty_item_id BIGINT       NOT NULL REFERENCES penalty_item (id),
    points          INT          NOT NULL,
    reason          VARCHAR(500),
    source          VARCHAR(20)  NOT NULL CHECK (source IN ('KIOSK', 'ROUTINE', 'MANUAL')),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ★ 자동 부여 중복 방지. 예: 'ATTENDANCE:{enrollment_id}:{date}:{rule_id}'
    --   출결·루틴 이벤트가 중복 트리거되면 점수가 두 번 부여된다(키오스크 재태깅,
    --   배치 재실행, 다중 인스턴스). 애플리케이션 체크로는 동시 실행을 못 막으므로
    --   DB 유니크 제약으로 막는다. 수기 부여는 NULL.
    idempotency_key VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_penalty_idempotency
    ON penalty_point (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_penalty_enrollment ON penalty_point (enrollment_id, occurred_at DESC);

-- 방화벽 위반·제재 (상벌점과 연결되므로 penalty 뒤에 둔다)
CREATE TABLE firewall_violation (
    id                  BIGSERIAL   PRIMARY KEY,
    academy_id          BIGINT      NOT NULL REFERENCES academy (id),
    year                SMALLINT    NOT NULL,
    enrollment_id       BIGINT      NOT NULL REFERENCES student_enrollment (id),
    firewall_request_id BIGINT      REFERENCES firewall_request (id),
    penalty_point_id    BIGINT      REFERENCES penalty_point (id),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE firewall_restriction (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    year            SMALLINT    NOT NULL,
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),
    restricted_from TIMESTAMPTZ NOT NULL,
    restricted_until TIMESTAMPTZ NOT NULL,
    violation_count INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_firewall_restriction_active
    ON firewall_restriction (enrollment_id, restricted_until);

-- ⚠️ "2회 적발 시 2주 제한"의 누적 기준 기간(연간/학기/영구)이 미확인이다.
--    확정 전까지 violation_count 산정 로직을 고정하지 말 것.
