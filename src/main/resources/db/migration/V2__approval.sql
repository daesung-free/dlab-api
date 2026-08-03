-- V2: 공통 승인 라우팅
--
-- 설계 근거는 docs/entity-design.md E.
-- ★ firewall 안에 두지 않는다. 요구사항정의서 F-4.11-5는 사유신청·정기일정·방화벽을
--   하나의 라우팅 엔진에 태운다. firewall 안에 두면 사유신청에서 같은 로직을 또 짜게 된다.

CREATE TABLE approval_item (
    id                       BIGSERIAL   PRIMARY KEY,
    academy_id               BIGINT      NOT NULL REFERENCES academy (id),
    year                     SMALLINT    NOT NULL,
    request_type             VARCHAR(30) NOT NULL
                             CHECK (request_type IN ('FIREWALL_UNLOCK', 'ABSENCE_REASON', 'REGULAR_SCHEDULE')),
    -- 1차 승인 주체
    approver_type            VARCHAR(10) NOT NULL CHECK (approver_type IN ('PARENT', 'TEACHER', 'AUTO')),
    -- NULL이면 에스컬레이션 없음 (예: 정기일정은 학부모 단독 승인)
    timeout_minutes          SMALLINT,
    escalation_approver_type VARCHAR(10) CHECK (escalation_approver_type IN ('PARENT', 'TEACHER')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               BIGINT,
    is_deleted               BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_approval_item UNIQUE (academy_id, year, request_type),
    -- 타임아웃과 에스컬레이션 승인자는 같이 있거나 같이 없어야 한다
    CONSTRAINT ck_approval_escalation_pair
        CHECK ((timeout_minutes IS NULL) = (escalation_approver_type IS NULL))
);

COMMENT ON TABLE approval_item IS '승인 항목별 정책. 방화벽=PARENT→10분→TEACHER, 정기일정=PARENT 단독, 사유신청=TEACHER.';

CREATE TABLE approval_request (
    id                       BIGSERIAL   PRIMARY KEY,
    academy_id               BIGINT      NOT NULL REFERENCES academy (id),
    year                     SMALLINT    NOT NULL,
    approval_item_id         BIGINT      NOT NULL REFERENCES approval_item (id),
    enrollment_id            BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 상태는 이 4종뿐. "타임아웃 후 승인됨"은 상태가 아니라 결과 속성이라
    -- resolution_case가 담당한다. 섞으면 "에스컬레이션됐지만 거절"을 표현할 수 없다.
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    requested_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ── 정책 스냅샷 (신청 시점 값을 박아둔다) ──
    -- 정책이 바뀌거나 담임이 교체돼도 이미 처리된 건의 이력이 흔들리면 안 된다.
    timeout_minutes          SMALLINT    NOT NULL,
    -- requested_at + timeout_minutes. 승인 케이스 판별의 기준선.
    escalation_at            TIMESTAMPTZ NOT NULL,
    -- 신청 시점의 담당선생님. class_assignment → class_master.homeroom_employee_id로 자동 도출.
    escalation_employee_id   BIGINT      REFERENCES employee (id),

    -- ── 처리 결과 ──
    resolved_at              TIMESTAMPTZ,
    resolver_type            VARCHAR(10) CHECK (resolver_type IN ('PARENT', 'TEACHER')),
    resolver_account_id      BIGINT      REFERENCES account (id),
    -- PARENT_IN_TIME       : 타임아웃 전 학부모 승인 (정상)
    -- STAFF_AFTER_TIMEOUT  : 무응답으로 타임아웃 경과 후 담당선생님 승인
    -- STAFF_BEFORE_TIMEOUT : 타임아웃 전인데 담당선생님이 먼저 승인
    -- ★ 뒤 두 개는 학부모에게 나가는 문구가 서로 달라야 한다. 절대 합치지 말 것.
    resolution_case          VARCHAR(30)
                             CHECK (resolution_case IN ('PARENT_IN_TIME', 'STAFF_AFTER_TIMEOUT', 'STAFF_BEFORE_TIMEOUT')),
    reject_reason            VARCHAR(500),

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               BIGINT,
    is_deleted               BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 학생이 같은 유형으로 동시에 여러 건을 올리지 못하게 막는다
CREATE UNIQUE INDEX uq_approval_request_pending
    ON approval_request (enrollment_id, approval_item_id) WHERE status = 'PENDING';
CREATE INDEX idx_approval_request_academy_status ON approval_request (academy_id, status);
-- 에스컬레이션 대상 조회용 (타임아웃 지난 대기 건)
CREATE INDEX idx_approval_request_escalation ON approval_request (escalation_at) WHERE status = 'PENDING';

COMMENT ON COLUMN approval_request.resolution_case IS
    'STAFF_AFTER_TIMEOUT과 STAFF_BEFORE_TIMEOUT은 학부모 안내 문구가 서로 달라야 한다.';
COMMENT ON COLUMN approval_request.escalation_at IS
    '승인 시각을 이 값과 비교해 resolution_case를 판별한다.';

-- 동시성 메모:
--   학부모 승인과 담당선생님 승인이 정확히 같은 순간 들어올 수 있다.
--   상태 전이는 조회 후 저장이 아니라 조건부 UPDATE(WHERE status='PENDING')로 처리하고,
--   갱신행이 0이면 이미 처리된 것으로 본다. 낙관적 락(version 컬럼)은 쓰지 않는다 —
--   두 방식을 섞으면 어느 쪽이 실제로 동시성을 막는지 불분명해진다.
