-- V20260811140000: 승인 우선 승인자 · 자동 재승인 (I-20 · F-4.11-5)
--
-- ⚠️ 파일명이 `V<yyyyMMdd>_<HHmm>`이 아니라 14자리 타임스탬프인 이유:
--    Flyway가 버전을 숫자로 비교하는데 `V20260811_1400`은 20260811.1400으로,
--    `V20260805220000`은 20260805220000으로 파싱된다. 8자리+소수 쪽이 항상 작아
--    **밑줄 방식 파일이 14자리 방식 파일보다 먼저 실행된다.**
--    이 마이그레이션은 terms(V20260805220000)를 FK로 참조하므로 뒤에 와야 한다.
--
-- 0803 대성 답변서로 승인 구조가 확장됐다.
--   ① 등록 시 우선 승인자(직원/학부모)를 학생마다 고르고 안내·동의를 받는다
--   ② 학부모 미응답 시 1회 자동 재승인 요청 → 그래도 무응답이면 직원에게 이양
--   ③ 타임아웃 10분은 지점별 설정 (approval_item.timeout_minutes로 이미 충족)
--
-- ★ 기존 동작은 "학부모 우선 + 타임아웃 후 담임도 승인 가능"이었다. 승인 주체가
--   지점 단위(approval_item.approver_type)로만 정해져 학생별로 고를 수 없었다.

-- ─────────────────────────────────────────────────────────────
-- 1. 학생별 우선 승인자 + 동의 이력
-- ─────────────────────────────────────────────────────────────
--
-- ★ 덮어쓰지 않고 행을 쌓는다. 이건 단순 설정이 아니라 동의다 —
--   "학부모가 10분 안에 응답하지 않으면 직원에게 승인권이 넘어간다"에 동의한 기록이라,
--   나중에 바꿨다고 이전 동의를 지우면 그 기간에 무엇에 동의했는지 답할 수 없다.
--   약관(term_agreement)과 같은 방식이다.

CREATE TABLE approver_preference (
    id               BIGSERIAL   PRIMARY KEY,
    academy_id       BIGINT      NOT NULL REFERENCES academy (id),
    year             SMALLINT    NOT NULL,
    enrollment_id    BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 우선 승인자. AUTO는 사람이 고를 수 있는 값이 아니라 제외한다
    preferred        VARCHAR(10) NOT NULL CHECK (preferred IN ('PARENT', 'TEACHER')),

    -- 동의 시각. 선택과 동의는 한 번에 이뤄지므로 NOT NULL이다
    agreed_at        TIMESTAMPTZ NOT NULL,

    -- 동의한 안내 문구의 버전. 문구가 미확정이라 지금은 NULL이고,
    -- 확정되면 terms 테이블에 코드를 하나 넣고 여기서 참조한다
    terms_id         BIGINT      REFERENCES terms (id),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 현재값 = 가장 최근 행. 조회가 이 순서로만 돈다
CREATE INDEX idx_approver_preference_current
    ON approver_preference (enrollment_id, agreed_at DESC)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE approver_preference IS
    '학생별 우선 승인자 선택 + 동의 이력. append-only — 현재값은 가장 최근 행';
COMMENT ON COLUMN approver_preference.terms_id IS
    '동의한 안내 문구 버전. 문구 확정 전까지 NULL';

-- ─────────────────────────────────────────────────────────────
-- 2. 승인 요청 — 우선 승인자 스냅샷 · 재요청 · 이양
-- ─────────────────────────────────────────────────────────────

-- 신청 시점의 우선 승인자. 학생이 나중에 바꿔도 이미 처리된 건의 이력이 흔들리면 안 된다.
-- 기존 행은 전부 학부모 우선이었으므로 PARENT로 채운다
ALTER TABLE approval_request
    ADD COLUMN primary_approver VARCHAR(10) NOT NULL DEFAULT 'PARENT'
        CHECK (primary_approver IN ('PARENT', 'TEACHER'));

-- 1회 자동 재승인 요청을 보낸 시각. NULL이면 아직 안 보냈다.
-- ★ 컬럼 하나로 "보냈는가"와 "언제 보냈는가"를 같이 답한다 —
--   횟수 컬럼을 따로 두면 "2회 이상 보내지 않는다"는 규칙이 코드에만 남는다
ALTER TABLE approval_request ADD COLUMN reminder_sent_at TIMESTAMPTZ;

-- 직원에게 승인권이 넘어간 시각. 재요청 후에도 무응답이면 기록된다
ALTER TABLE approval_request ADD COLUMN handed_over_at TIMESTAMPTZ;

COMMENT ON COLUMN approval_request.primary_approver IS
    '신청 시점 우선 승인자 스냅샷. 학생 선택(approver_preference) > 지점 정책(approval_item) 순';
COMMENT ON COLUMN approval_request.reminder_sent_at IS
    '자동 재승인 요청 발송 시각. NULL이면 미발송 — 이 컬럼이 곧 "1회만" 보장이다';
COMMENT ON COLUMN approval_request.handed_over_at IS
    '재요청 후에도 무응답이라 직원에게 승인권이 넘어간 시각';

-- 스케줄러가 "재요청 보낼 것"과 "이양할 것"을 각각 훑는다
CREATE INDEX idx_approval_request_reminder
    ON approval_request (escalation_at)
    WHERE status = 'PENDING' AND reminder_sent_at IS NULL;

CREATE INDEX idx_approval_request_handover
    ON approval_request (reminder_sent_at)
    WHERE status = 'PENDING' AND handed_over_at IS NULL;

-- ─────────────────────────────────────────────────────────────
-- 3. 결과 케이스에 "우선 승인자가 직원" 추가
-- ─────────────────────────────────────────────────────────────
--
-- ★ 기존 STAFF_BEFORE_TIMEOUT(시간 남았는데 담임이 먼저 승인)과 합치면 안 된다.
--   우선 승인자가 직원이면 애초에 학부모가 기다리는 상황이 아니라,
--   "시간이 남았지만 담임이 먼저 승인했습니다"라는 문구가 학부모에게 나가면 이상하다.

ALTER TABLE approval_request DROP CONSTRAINT IF EXISTS approval_request_resolution_case_check;

ALTER TABLE approval_request
    ADD CONSTRAINT approval_request_resolution_case_check
        CHECK (resolution_case IN (
            'PARENT_IN_TIME',
            'STAFF_AFTER_TIMEOUT',
            'STAFF_BEFORE_TIMEOUT',
            'STAFF_PRIMARY'
        ));

COMMENT ON COLUMN approval_request.resolution_case IS
    'PARENT_IN_TIME(정상) / STAFF_AFTER_TIMEOUT(무응답 후 담임) / STAFF_BEFORE_TIMEOUT(시간 남았는데 담임) / STAFF_PRIMARY(우선 승인자가 직원). 문구가 전부 달라야 한다';

-- ─────────────────────────────────────────────────────────────
-- 4. 새 알림 이벤트 3종
-- ─────────────────────────────────────────────────────────────
--
-- 문구는 미확정이라(I-4 · E-5) 등록만 해둔다. content_confirmed가 false인 동안
-- 실제 발송은 SKIPPED로 남고, 문구가 확정되면 그때 채운다.
--
-- ★ 승인 완료 알림을 케이스별로 나눠 두는 이유는 문구가 전부 달라야 하기 때문이다.
--   "우선 승인자가 직원이라 직원이 승인"과 "시간이 남았는데 담임이 먼저 승인"을
--   같은 문구로 보내면, 후자를 받아야 할 학부모가 전자를 받고 혼란스러워한다.

INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    -- 학부모 무응답 → 1회 자동 재요청. 독촉이라 알림톡으로 간다
    ('APPROVAL_REMINDER',                  'KAKAO_ALIMTALK', 'studentName,requestedAt,timeoutMinutes'),
    -- 재요청 후에도 무응답 → 직원에게 이양. 담당선생님과 학부모 양쪽이 받는다
    ('APPROVAL_HANDED_OVER',               'KAKAO_ALIMTALK', 'studentName,requestedAt,timeoutMinutes'),
    -- 우선 승인자가 직원이라 직원이 승인함. 학부모에겐 통지 성격이라 푸시로 충분하다
    ('APPROVAL_APPROVED_BY_STAFF_PRIMARY', 'FCM_PUSH',       'studentName,resolvedAt');
