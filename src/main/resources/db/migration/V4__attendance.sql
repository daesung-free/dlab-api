-- V4: 출결 (등원 기록 + 사전 결석사유)
-- 참고: CLAUDE.md §4 — 자리이탈은 두 입력 경로(키오스크 재태깅 vs 앱)의 충돌 규칙이 미확정 블로커라
--   이 마이그레이션에 포함하지 않는다. 규칙 확정 후 별도 버전으로 추가할 것.

CREATE TABLE attendance_record (
    id              BIGSERIAL   PRIMARY KEY,
    branch_id       BIGINT      NOT NULL REFERENCES branch (id),
    student_id      BIGINT      NOT NULL REFERENCES student (id),
    attendance_date DATE        NOT NULL,
    checked_in_at   TIMESTAMPTZ NOT NULL,
    -- KIOSK(카드 태깅) / APP / MANUAL(관리자 수기 등록)
    source          VARCHAR(20) NOT NULL DEFAULT 'KIOSK',
    -- PRESENT(정상 등원) / LATE(지각)
    status          VARCHAR(20) NOT NULL DEFAULT 'PRESENT',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 하루 첫 등원 기록만 남긴다. 재태깅은 무시(미등원 판정 기준이 흔들리면 안 됨).
    CONSTRAINT uq_attendance_per_day UNIQUE (student_id, attendance_date)
);

CREATE INDEX idx_attendance_branch_date ON attendance_record (branch_id, attendance_date);

-- 사전 제출 결석/지각 사유. 미등원 알림 배치는 여기 승인/대기 건이 있는 학생을 제외한다
-- (무단결석만 알림 대상 — CLAUDE.md §3).
CREATE TABLE absence_reason (
    id           BIGSERIAL    PRIMARY KEY,
    branch_id    BIGINT       NOT NULL REFERENCES branch (id),
    student_id   BIGINT       NOT NULL REFERENCES student (id),
    target_date  DATE         NOT NULL,
    -- ABSENCE(결석) / LATE(지각) / EARLY_LEAVE(조퇴)
    reason_type  VARCHAR(20)  NOT NULL,
    content      VARCHAR(500) NOT NULL,
    -- PENDING(관리자 승인 대기) / APPROVED / REJECTED
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    submitted_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at  TIMESTAMPTZ,
    reviewer_account_id BIGINT REFERENCES user_account (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_absence_reason_student_date ON absence_reason (student_id, target_date);
CREATE INDEX idx_absence_reason_branch_date ON absence_reason (branch_id, target_date);
