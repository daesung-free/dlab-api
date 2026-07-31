-- V2: 방화벽(와이파이) 해제 신청 · 승인
-- 참고: CLAUDE.md §3 — 단순 "먼저 승인한 쪽이 이김" 레이스가 아니라 타임아웃 기반 에스컬레이션 모델.
--   기본 승인자는 학부모, 타임아웃(10분) 경과 시 담당선생님(사감)이 에스컬레이션 승인.
--   승인 결과는 3케이스로 갈리고 케이스별 안내 문구가 달라야 하므로 결과를 컬럼으로 남긴다.

CREATE TABLE firewall_request (
    id                    BIGSERIAL   PRIMARY KEY,
    branch_id             BIGINT      NOT NULL REFERENCES branch (id),
    student_id            BIGINT      NOT NULL REFERENCES student (id),
    reason                VARCHAR(500),

    -- PENDING / APPROVED / REJECTED / CANCELED
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ── 승인자 · 타임아웃 정책 (신청 시점 값을 스냅샷으로 고정) ──
    -- 기본 승인자 유형. 현재 정책상 항상 PARENT지만, 정책이 바뀔 수 있어 컬럼으로 둔다.
    default_approver_type VARCHAR(20) NOT NULL DEFAULT 'PARENT',
    -- 타임아웃 값(분). 현재 확정값 10분. 지점/정책별로 달라질 수 있어 신청건에 박아둔다.
    timeout_minutes       INT         NOT NULL DEFAULT 10,
    -- requested_at + timeout_minutes. 승인 시각을 이 값과 비교해 3케이스를 판별한다.
    escalation_at         TIMESTAMPTZ NOT NULL,
    -- 에스컬레이션 승인자(담당선생님·사감). 신청 시점의 "학생 → 반 → 담임"으로 결정된 값을 스냅샷.
    -- 나중에 반 담임이 바뀌어도 이 신청의 승인 이력은 흔들리지 않아야 한다.
    escalation_staff_id   BIGINT      REFERENCES staff (id),

    -- ── 처리 결과 ──
    resolved_at           TIMESTAMPTZ,
    -- 실제로 승인/거절한 주체
    resolver_account_id   BIGINT      REFERENCES user_account (id),
    -- PARENT / STAFF
    resolver_type         VARCHAR(20),
    -- PARENT_IN_TIME       : 타임아웃 전 학부모 승인 (정상)
    -- STAFF_AFTER_TIMEOUT  : 학부모 무응답으로 타임아웃 경과 후 담당선생님 승인
    -- STAFF_BEFORE_TIMEOUT : 타임아웃 전인데 담당선생님이 먼저 승인 (문구가 위와 달라야 함)
    resolution_case       VARCHAR(30),
    reject_reason         VARCHAR(500),

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 학생당 처리 대기중인 신청은 1건만 허용 (중복 신청 방지)
CREATE UNIQUE INDEX uq_firewall_request_pending
    ON firewall_request (student_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_firewall_request_branch_status ON firewall_request (branch_id, status);
CREATE INDEX idx_firewall_request_escalation ON firewall_request (escalation_at) WHERE status = 'PENDING';

COMMENT ON COLUMN firewall_request.resolution_case IS
    '승인 케이스. STAFF_AFTER_TIMEOUT과 STAFF_BEFORE_TIMEOUT은 학부모 안내 문구가 서로 달라야 한다.';
