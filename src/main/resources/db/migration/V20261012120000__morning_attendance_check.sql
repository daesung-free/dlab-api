-- 오전 출근 확인 (긴급출입 개발건 기능 B).
--
-- 매일 07:05 에 지점별 당일 출근 기록(IN)이 하나도 없으면 관리자에게 문자가 간다.
-- 「지점에 아무도 출근하지 않아 학생이 문 앞에 서 있는」 상황을 잡으려는 것이다.
--
-- ★ 검사 결과를 남기는 이유 — 「발송했는데 안 왔다」와 「검사가 안 돌았다」를 가려야 한다.
--   기록이 없으면 그 둘이 구분되지 않고, 아침마다 사람이 로그를 뒤지게 된다.

CREATE TABLE morning_attendance_check
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT    NOT NULL,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),

    check_date      DATE        NOT NULL,
    checked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 그 시각까지 들어온 출근(IN) 건수. 0 이면 발송 대상이다.
    -- 건수를 남기는 이유는 1 과 0 의 경계에서 "정말 아무도 없었나"를 되짚을 수 있어야 해서다
    in_count        INTEGER     NOT NULL,

    -- 발송 대상이었는가(= in_count 가 0 이었는가)
    alert_needed    BOOLEAN     NOT NULL,
    -- 실제로 보냈는가. ⚠️ 알리고 연동 전에는 늘 false 다 — 그 사실이 기록에 남아야
    -- "왜 문자가 안 왔나"에 답할 수 있다
    alert_sent      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 보낼 대상 계정 수. 0 이면 권한을 가진 계정이 없다는 뜻이고, 그건 설정 문제다
    recipient_count INTEGER     NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,

    -- ★ 하루 한 지점 한 건. 배치가 두 번 돌거나 손으로 다시 돌려도 중복되지 않는다 —
    --   중복되면 "몇 번 보냈나"가 흐려지고 조회 화면에 같은 날이 두 줄로 뜬다
    CONSTRAINT uq_morning_check UNIQUE (academy_id, check_date)
);

CREATE INDEX idx_morning_check_date ON morning_attendance_check (check_date DESC, academy_id);

COMMENT ON TABLE morning_attendance_check IS
    '오전 출근 확인 결과. 휴일 구분 없이 매일 한 번(2026-10-08 디랩 결정)';
