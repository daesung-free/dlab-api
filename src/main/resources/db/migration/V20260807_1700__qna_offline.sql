-- ==========================================================================
-- 질의응답 — 대면(OFF) (F-4.11-7 관리 · 앱 A-13)
--
--   qna_offline_slot         상담실 가능 타임
--   qna_offline_reservation  학생 예약
--
-- ★ 온라인(ON)은 만들지 않는다. 시트가 스스로 미확정이라고 적어놨다 —
--   "멘토 배정 규칙·답변 SLA·첨부 허용 여부 확정 필요",
--   "1:1채팅(F-4.11-3)과 기능 경계 선결정 필요(별도 도메인 유지 vs 채팅 흡수)".
--   ▷[0803] 회신에도 "온라인은 현재 미운영, 추후 대비"로 되어 있다.
--   지금 만들면 확정 후 되돌릴 가능성이 크다.
--
-- ⚠️ 시트의 ON/OFF는 켜짐/꺼짐이 아니라 온라인/오프라인의 약칭이다.
-- ==========================================================================


-- ==========================================================================
-- 1. 가능 타임 (슬롯)
-- ==========================================================================

-- ★ "간격"을 스키마에 박지 않는다.
--   ▷[0803] 회신이 "현재 15분 간격 운영, 변동 가능"이라 했다. 간격을 컬럼이나 상수로 두면
--   바뀔 때 마이그레이션이 필요해진다. 대신 슬롯을 <b>시작·종료 시각을 가진 행</b>으로 두고,
--   개설 API가 간격을 받아 행을 여러 개 만든다 — 간격이 바뀌어도 데이터만 달라진다.
CREATE TABLE qna_offline_slot (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,
    slot_date   DATE        NOT NULL,
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    -- 상담 담당. NULL이면 미배정 — 슬롯만 먼저 열고 담당을 나중에 정하는 운영이 있다.
    teacher_id  BIGINT      REFERENCES teacher (id),
    room        VARCHAR(50),
    -- 슬롯당 인원. 1:1 상담이면 1이다.
    capacity    SMALLINT    NOT NULL DEFAULT 1 CHECK (capacity > 0),
    -- 닫으면 새 예약을 안 받는다. 기존 예약은 그대로 둔다 —
    -- 삭제하면 이미 예약한 학생의 기록이 사라진다.
    closed      BOOLEAN     NOT NULL DEFAULT FALSE,
    memo        VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_qna_slot_time CHECK (start_time < end_time)
);

COMMENT ON TABLE qna_offline_slot IS
    '상담실 가능 타임. 간격(현재 15분)을 스키마에 두지 않는다 — 개설 API가 행을 여러 개 만든다.';
COMMENT ON COLUMN qna_offline_slot.closed IS
    '새 예약 차단. 기존 예약은 유지된다 — 삭제하면 예약한 학생 기록이 사라진다.';

-- 같은 날 같은 시각에 같은 상담실이 두 번 열리면 안 된다.
-- room이 NULL인 경우(상담실 구분 없음)는 시각만으로 묶는다.
CREATE UNIQUE INDEX uq_qna_slot
    ON qna_offline_slot (academy_id, slot_date, start_time, COALESCE(room, ''))
 WHERE is_deleted = FALSE;

-- 앱은 "이 날짜에 열린 타임"을 본다.
CREATE INDEX idx_qna_slot_date
    ON qna_offline_slot (academy_id, slot_date, start_time) WHERE is_deleted = FALSE;


-- ==========================================================================
-- 2. 예약
-- ==========================================================================

CREATE TABLE qna_offline_reservation (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    slot_id       BIGINT      NOT NULL REFERENCES qna_offline_slot (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 무엇을 물어볼지. ▷[0803] 회신의 "대면 신청 시 질문 입력"이다.
    -- ⚠️ 시트에는 없다(회신서에만 있음). 다만 없으면 담당이 준비를 못 하고,
    --    컬럼 하나라 확정 후에도 손댈 일이 적어 미리 둔다. NULL 허용.
    question      VARCHAR(500),
    -- 취소를 물리 삭제하지 않는다 — "몇 번 예약했다 취소했나"가 운영 판단 근거가 된다.
    canceled_at   TIMESTAMPTZ,
    reserved_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE qna_offline_reservation IS '대면 상담 예약. 취소는 soft delete가 아니라 canceled_at으로 남긴다.';
COMMENT ON COLUMN qna_offline_reservation.question IS
    '▷[0803] 회신의 "질문 입력". 시트 미반영 항목이라 NULL 허용.';

-- ★ 한 학생이 같은 슬롯을 두 번 잡을 수 없다. 단 취소분은 제외해 재예약이 가능하다 —
--   전부 막으면 한 번 취소한 학생이 그 시간대에 영영 못 들어온다.
CREATE UNIQUE INDEX uq_qna_reservation
    ON qna_offline_reservation (slot_id, enrollment_id)
 WHERE canceled_at IS NULL AND is_deleted = FALSE;

-- 정원 확인·현황 조회
CREATE INDEX idx_qna_reservation_slot
    ON qna_offline_reservation (slot_id) WHERE canceled_at IS NULL AND is_deleted = FALSE;

-- 학생 본인 예약 목록
CREATE INDEX idx_qna_reservation_student
    ON qna_offline_reservation (enrollment_id, reserved_at DESC);
