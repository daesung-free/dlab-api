-- 긴급 출입 요청 기록 (긴급출입 1-6 · 관리자 웹 10번).
--
-- 학생이 출입문 QR 로 번호를 요청한 내역이다. 제공·거절 모두 남긴다 —
-- 거절 건을 빼면 **번호를 두드려 보는 시도**가 기록에서 사라진다.

CREATE TABLE door_code_request
(
    id             BIGSERIAL PRIMARY KEY,
    year           SMALLINT    NOT NULL,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),

    -- ★ 판정 결과. 학생에게는 전부 「확인할 수 없습니다」로 보이지만,
    --   관리자 내역에서는 갈라야 담당자가 전화를 받고 원인을 안다
    result         VARCHAR(30) NOT NULL,

    -- 찾았을 때만 채운다. 못 찾은 거절 건에는 없다
    enrollment_id  BIGINT      REFERENCES student_enrollment (id),

    -- ★ 입력한 번호를 마스킹해 남긴다. 평문으로 두면 요청 기록이 곧 연락처 목록이 되고,
    --   아예 안 남기면 거절 건에서 「누가 시도했나」를 전혀 알 수 없다
    phone_masked   VARCHAR(20),

    -- 제공한 번호가 어느 이력이었나. ★ 번호 자체는 남기지 않는다(암호화의 의미가 사라진다)
    door_code_id   BIGINT      REFERENCES branch_door_code (id),

    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 공개 경로라 로그인 주체가 없다. 공격 추적에 필요해 IP 를 남긴다
    requester_ip   VARCHAR(45),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_door_request_list ON door_code_request (academy_id, requested_at DESC);

COMMENT ON TABLE door_code_request IS
    '긴급 출입 번호 요청 내역. 제공·거절 모두 남긴다 — 거절을 빼면 대입 시도가 안 보인다';
COMMENT ON COLUMN door_code_request.result IS
    'PROVIDED · NOT_FOUND · DUPLICATE_PHONE · NOT_ENROLLED · OUTSIDE_WINDOW · NO_CODE_REGISTERED · RATE_LIMITED';
