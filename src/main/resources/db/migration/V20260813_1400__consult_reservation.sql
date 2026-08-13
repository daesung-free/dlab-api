-- V20260813_1400: 상담 예약 (F-4.11-4, 0803 답변서 신규)
--
-- ① 담임이 상담 가능 일정·시간을 설정하고 노출 → ② 학생이 노출된 슬롯을 선택(예약)
-- → ③ 선택한 일정에 알림 연동.
--
-- ★ 질의응답 대면 예약(qna_offline_slot)과 구조가 같지만 합치지 않는다.
--   ㆍ질의응답은 "상담실"이 주체다 — 슬롯에 담당이 없을 수 있고(teacher NULL 허용)
--     학생은 지점의 아무 타임이나 잡는다.
--   ㆍ상담은 "담임"이 주체다 — 학생은 자기 담임 슬롯만 예약할 수 있어야 한다.
--     합쳐서 teacher를 nullable로 두면 이 제약이 코드에만 남아, 한 곳만 빠뜨려도
--     아무 담임에게나 예약이 걸린다.
--   ㆍ상담은 유형(정기·성적·생활·진학·학부모)이 있고 상담 일지로 이어진다.

-- ─────────────────────────────────────────────────────────────
-- 1. 담임 가능 일정
--
-- ★ 간격을 스키마에 두지 않는다. 질의응답과 같은 판단이다 — 시작·종료·간격을 받아
--   슬롯 행을 여러 개 만든다. 간격이 바뀌어도 데이터만 달라진다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE consult_slot (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,

    -- ★ NOT NULL이다. 상담은 담임이 주체이고, 학생은 자기 담임 슬롯만 볼 수 있다.
    --   행정(employee)은 상담을 하지 않는다 — teacher FK가 곧 "담당선생님 보장"이다
    teacher_id  BIGINT      NOT NULL REFERENCES teacher (id),

    slot_date   DATE        NOT NULL,
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,

    -- 1:1 상담이면 1. 학부모 상담에 부모가 함께 오는 경우가 있어 열어둔다
    capacity    SMALLINT    NOT NULL DEFAULT 1 CHECK (capacity >= 1),

    -- ★ 노출은 명시적으로 켠다(기본 FALSE). 요구사항이 "설정 및 노출"로 두 단계라,
    --   만들자마자 보이면 담임이 일정을 짜는 중간 상태가 학생에게 그대로 노출된다.
    --
    --   마감도 이 값을 내려서 한다 — 별도 closed 컬럼을 두면 "안 켠 것"과 "닫은 것"이
    --   학생 화면에서 똑같이 안 보이는데 상태만 둘이 된다.
    --   이미 잡힌 예약은 내려도 유효하다(지우면 학생 기록이 사라진다)
    published   BOOLEAN     NOT NULL DEFAULT FALSE,

    place       VARCHAR(50),
    memo        VARCHAR(200),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 담임이 같은 시각을 두 번 열지 못하게. 두 벌이면 학생 화면에 같은 타임이
-- 두 줄로 뜨고 어느 쪽에 예약됐는지 담임이 알 수 없다
CREATE UNIQUE INDEX uq_consult_slot
    ON consult_slot (teacher_id, slot_date, start_time) WHERE is_deleted = FALSE;

CREATE INDEX idx_consult_slot_lookup
    ON consult_slot (academy_id, slot_date, teacher_id) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 2. 예약
-- ─────────────────────────────────────────────────────────────
CREATE TABLE consult_reservation (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    slot_id       BIGINT      NOT NULL REFERENCES consult_slot (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 상담 유형 5종. 상담 일지(consult_log.consult_type)와 같은 값이라 이어 쓸 수 있다
    consult_type  VARCHAR(20) NOT NULL
                  CHECK (consult_type IN ('REGULAR','SCORE','LIFE','ADMISSION','PARENT')),

    -- 무엇을 상담하고 싶은지. 없으면 담임이 준비를 못 한다
    request_note  VARCHAR(500),

    reserved_at   TIMESTAMPTZ NOT NULL,
    -- ★ 취소를 물리 삭제하지 않는다 — "몇 번 잡았다 취소했나"가 운영 판단 근거가 된다
    canceled_at   TIMESTAMPTZ,

    -- 상담을 마치고 담임이 쓴 일지. NULL이면 아직 안 썼거나 노쇼다.
    -- ★ consult_log 쪽에 예약 컬럼을 더하지 않고 여기서 가리킨다 —
    --   일지는 예약 없이도 만들어지므로(전화 상담 등) 그쪽에 두면 대부분 NULL이 된다
    consult_log_id BIGINT     REFERENCES consult_log (id),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 슬롯을 같은 학생이 두 번 잡지 못하게. 취소분은 제외해야 재예약이 된다
CREATE UNIQUE INDEX uq_consult_reservation
    ON consult_reservation (slot_id, enrollment_id)
    WHERE is_deleted = FALSE AND canceled_at IS NULL;

CREATE INDEX idx_consult_reservation_slot
    ON consult_reservation (slot_id) WHERE is_deleted = FALSE;

CREATE INDEX idx_consult_reservation_mine
    ON consult_reservation (enrollment_id, reserved_at) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 3. 알림 템플릿
--
-- ★ 문구는 비워 둔다(I-4 미확정). NotificationService가 문구 없는 템플릿은 이력만 남기고
--   실제 발송을 건너뛰므로, 지금 넣어도 아무에게도 가지 않는다.
--
-- ★ 학생명 변수는 필수다 — 담임이 여러 학생을 맡으므로 누구 예약인지 없으면 못 읽는다.
--
-- ⚠️ "호출" 연동(상담 시각에 학생을 부르는 것)은 만들지 않았다. 몇 분 전에 부를지가
--    정해지지 않아 기본값을 정하면 그 값이 그대로 운영에 굳는다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    -- 학생이 예약하면 담임에게. 즉시 알아야 일정을 비워둘 수 있어 푸시로 간다
    ('CONSULT_RESERVED', 'FCM_PUSH', 'studentName,slotDate,startTime,consultType'),
    -- 취소도 담임에게. 없으면 빈 자리를 모른 채 그 시간을 비워둔다
    ('CONSULT_CANCELED', 'FCM_PUSH', 'studentName,slotDate,startTime');
