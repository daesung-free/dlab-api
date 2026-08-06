-- V20260806_1500: 관리자 출결 정정 (F-4.3-1 "개별 수정")
--
-- ★ 원장(attendance_tagging_log)은 고치지 않는다.
--   그건 "키오스크에 실제로 찍혔다"는 사실 기록이라, 사람이 값을 바꾸면
--   그 사실 자체가 사라진다. 태깅 누락 보정은 source='MANUAL'로 **새 행을 추가**한다 —
--   기존 KIOSK_NFC 행과 섞이지 않으므로 "찍은 것"과 "사람이 넣은 것"이 계속 구분된다.
--
-- ★ 여기서 고치는 건 파생 상태(attendance_daily_status)뿐이다.

-- 관리자가 손댄 행은 배치가 다시 덮으면 안 된다.
--
-- 확정 배치는 매일 새벽 2시에 돌면서 reconfirm()으로 기존 행을 덮어쓴다.
-- 이 플래그가 없으면 관리자가 낮에 "결석 → 정상등원"으로 고쳐놔도
-- **다음 날 새벽에 조용히 결석으로 되돌아간다.** 화면에는 정정된 값이 보이다가
-- 하루 뒤 원래대로 돌아가므로 아무도 원인을 못 찾는다.
ALTER TABLE attendance_daily_status
    ADD COLUMN manually_modified BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN attendance_daily_status.manually_modified IS
    '관리자 정정분. TRUE면 확정 배치가 건너뛴다 — 덮으면 정정이 사라진다';

-- 정정 이력.
--
-- ★ 컬럼 하나(modified_by)로 못 끝내는 이유: 같은 날을 여러 번 고칠 수 있다.
--   컬럼이면 마지막 정정만 남아 "결석 → 정상 → 다시 결석"의 중간이 사라진다.
--   출결은 벌점·미등원 알림·출결률의 근거라 "누가 언제 무엇을 무엇으로 바꿨나"가
--   전부 남아야 한다(보안심사 직결).
CREATE TABLE attendance_modification (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    year            SMALLINT    NOT NULL,
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),
    attendance_date DATE        NOT NULL,

    -- 상태 정정. 태깅 추가만 한 경우에는 둘 다 NULL이다
    before_status   VARCHAR(20),
    after_status    VARCHAR(20),
    before_excused  BOOLEAN,
    after_excused   BOOLEAN,

    -- 태깅 보정. 상태 정정만 한 경우에는 NULL이다
    added_event     VARCHAR(20),
    added_at        TIMESTAMPTZ,

    -- ★ 사유는 필수다. 없으면 이력이 "누군가 바꿨다"까지만 말해주고
    --   왜 바꿨는지는 영영 알 수 없어 감사 자료가 되지 못한다
    reason          VARCHAR(200) NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 화면이 "이 학생의 이 날"로 이력을 편다
CREATE INDEX idx_attendance_modification_lookup
    ON attendance_modification (enrollment_id, attendance_date);

-- 감사 조회는 지점·기간으로 훑는다
CREATE INDEX idx_attendance_modification_audit
    ON attendance_modification (academy_id, attendance_date);

COMMENT ON TABLE attendance_modification IS
    '관리자 출결 정정 이력. 원장은 고치지 않으므로 여기가 유일한 추적 경로다';
COMMENT ON COLUMN attendance_modification.reason IS
    '정정 사유(필수). 없으면 감사 자료가 되지 못한다';
COMMENT ON COLUMN attendance_modification.added_event IS
    '태깅 보정으로 추가한 이벤트. 원장에는 source=MANUAL 행으로 들어간다';
