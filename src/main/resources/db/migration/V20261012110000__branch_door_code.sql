-- 지점별 도어락 번호 (긴급출입 개발건 1-5, 원문 `DLAB_긴급출입_오전출근확인_개발요구사항_v2`).
--
-- 7시에 문이 안 열렸을 때 학생이 출입문 QR 로 번호를 받아 간다. 관리자가 미리 저장해 둔
-- 번호를 06:59~08:00 안에서만 제공하고, 08:00 이후 지점 관리자가 실물 도어락을 교체하고
-- 새 번호를 여기에 넣는다.

CREATE TABLE branch_door_code
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT    NOT NULL,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),

    -- ★ 평문으로 두지 않는다. DB 가 새면 전 지점 출입문이 열린다.
    --   AES-GCM 이라 같은 번호를 넣어도 매번 다른 값이 된다(그게 맞다 —
    --   같으면 "같은 번호를 쓴다"는 사실이 드러난다)
    code_encrypted  VARCHAR(200) NOT NULL,

    -- ★★ 재사용 판정용. 암호문끼리는 비교가 안 되므로 결정적인 값이 하나 더 필요하다.
    --   ⚠️ 단순 해시가 아니라 **HMAC** 이다 — 도어락 번호는 네 자리인 경우가 많아
    --   SHA-256 이면 1만 번만 돌려 보면 되므로 사실상 평문이다. 비밀키를 섞어야 한다
    code_hash       VARCHAR(100) NOT NULL,

    -- 교체 시각. 「지금 번호」는 지점에서 가장 최근 행이다 —
    -- current 플래그를 두지 않는 이유는 교체가 곧 새 행이고 순서가 그대로 이력이기 때문
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,

    -- ★ 재사용 차단. is_deleted 를 조건에 넣지 않는다 —
    --   지운 번호도 과거에 쓴 번호라 다시 쓰면 안 된다
    CONSTRAINT uq_branch_door_code UNIQUE (academy_id, code_hash)
);

CREATE INDEX idx_branch_door_code_current ON branch_door_code (academy_id, applied_at DESC);

COMMENT ON TABLE branch_door_code IS
    '지점 도어락 번호 이력. 덮어쓰지 않고 쌓는다 — 그때 어떤 번호였나에 답해야 한다';
