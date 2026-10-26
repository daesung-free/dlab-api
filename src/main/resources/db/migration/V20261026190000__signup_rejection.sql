-- 학생 가입 반려 (요구사항정의서 A-2 · F-4.12-1).
--
-- ★★ 상태머신에 반려가 없었다. 시트는 「반려 사유를 기록하고 재신청한다」를 요구하는데
--   상태값이 PENDING → ACTIVE 이분법뿐이라, 반려하면 계정이 영원히 PENDING 으로 남았다.
--   관리자는 거절할 방법이 없고, 학생은 왜 안 되는지 알 수 없었다.
--
-- ★ 전달 경로는 「앱에서 보여주는 것」으로 간다. 승인 전에는 로그인이 막히므로
--   알림톡 말고는 길이 없어 보였는데, 실제로는 둘이 있다.
--     ① 로그인 시도 → SIGNUP_REJECTED + 사유 (비밀번호가 맞아야 응답을 받는다)
--     ② 재가입 시도 → 중복방지에 걸릴 때 사유를 보여주고 다시 넣게 한다
--   ⚠️ 무인증 「가입 상태 조회」는 만들지 않는다 — 전화번호만으로 학생 고유ID 가
--      새어나가고, 그 ID 를 아는 사람은 누구나 남의 자녀로 연결할 수 있다(§3).
--   알림톡으로 즉시 알리는 것은 템플릿 심사(4~5일)가 필요해 뒤로 둔다.
--
-- ⚠️ 사유를 계정이 아니라 별도 행으로 쌓는다. 재신청이 여러 번 가능하므로
--   마지막 사유만 남기면 「몇 번 반려됐는지」에 답할 수 없다 — 약관 동의와 같은 판단이다.

-- ⚠️ V1 의 인라인 CHECK 를 먼저 떼야 한다. 이름을 주지 않아 PostgreSQL 이
--   account_status_check 로 붙였고(운영에서 확인), 그냥 ADD 하면 제약이 둘이 되어
--   옛 제약이 'REJECTED' 를 계속 거부한다 — 「추가했는데 안 들어가는」 상태가 된다.
ALTER TABLE account DROP CONSTRAINT IF EXISTS account_status_check;

ALTER TABLE account
    ADD CONSTRAINT ck_account_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN', 'REJECTED'));

CREATE TABLE signup_rejection (
    id            BIGSERIAL    PRIMARY KEY,
    year          SMALLINT     NOT NULL,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    account_id    BIGINT       NOT NULL REFERENCES account (id),
    enrollment_id BIGINT       NOT NULL REFERENCES student_enrollment (id),

    -- ★ 사유는 필수다. 비워 두면 학생이 무엇을 고쳐야 하는지 알 수 없다
    reason        VARCHAR(500) NOT NULL,

    -- 재신청으로 이 반려가 해소된 시각. NULL 이면 아직 반려 상태다
    resolved_at   TIMESTAMPTZ,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 가장 최근 반려를 찾는 경로가 로그인·재가입 양쪽에서 쓰인다
CREATE INDEX ix_signup_rejection_account
    ON signup_rejection (account_id, id DESC)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE signup_rejection IS
    '학생 가입 반려 이력. 재신청이 여러 번 가능하므로 행으로 쌓는다 — 마지막 사유만 남기면 몇 번 반려됐는지에 답할 수 없다.';
COMMENT ON COLUMN signup_rejection.resolved_at IS
    '재신청으로 해소된 시각. NULL 이면 아직 반려 상태다.';
