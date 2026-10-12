-- 직원 출근 기록 수정 이력 (긴급출입 개발건 2-4).
--
-- 요구사항이 「수정 이력을 남깁니다」라고 하는데 **수정 기능 자체가 없었다.** 둘을 함께 만든다.
--
-- ★ 이력을 남기는 이유는 「이 사람이 진짜 그 시간에 왔는가」에 답하기 위해서다.
--   고친 값은 원본보다 신뢰도가 낮은데, 이력이 없으면 그 사실 자체가 사라진다.

CREATE TABLE staff_attendance_edit
(
    id                 BIGSERIAL PRIMARY KEY,
    year               SMALLINT    NOT NULL,
    academy_id         BIGINT      NOT NULL REFERENCES academy (id),
    attendance_id      BIGINT      NOT NULL REFERENCES staff_attendance (id),

    -- 전후값. 둘 다 남겨야 "무엇을 고쳤나"에 답할 수 있다 —
    -- 후값만 남기면 원본을 알 길이 없고, 전값만 남기면 지금 값이 맞는지 모른다
    before_event_type  VARCHAR(10) NOT NULL,
    before_recorded_at TIMESTAMPTZ NOT NULL,
    after_event_type   VARCHAR(10) NOT NULL,
    after_recorded_at  TIMESTAMPTZ NOT NULL,

    -- ★ 필수다. 요구사항의 「누가·언제·무엇을·왜」 중 **「왜」가 없으면 나머지 셋이
    --   쓸모없어진다** — 이유 없이 시각만 고치면 고친 값의 근거가 남지 않는다
    reason             VARCHAR(500) NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         BIGINT,
    is_deleted         BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_staff_att_edit_target ON staff_attendance_edit (attendance_id, id DESC);
CREATE INDEX idx_staff_att_edit_academy ON staff_attendance_edit (academy_id, created_at DESC);

COMMENT ON TABLE staff_attendance_edit IS
    '직원 출근 기록 수정 이력. 덮어쓰지 않고 쌓는다 — 여러 번 고친 경우도 순서가 남는다';
