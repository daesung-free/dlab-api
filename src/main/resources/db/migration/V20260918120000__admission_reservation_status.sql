-- 입학예약 진행 상태와 메모 (F-4.2-1 · 2026-09-18 답변서)
--
-- ★ 재적 상태(student_enrollment.enrollment_status)와 다른 축이다.
--   재적은 "학생이 지금 어떤 상태인가", 이건 "신청 건이 어디까지 처리됐나" 다.
--   두 축이 만나는 지점은 「입학확정」 하나뿐이고, 거기서 학생을 만든다.
ALTER TABLE admission_reservation
    ADD COLUMN consult_status VARCHAR(20) NOT NULL DEFAULT 'CALL_NEEDED'
        CHECK (consult_status IN ('CALL_NEEDED', 'CANCELED', 'CONSULTED',
                                  'ON_HOLD', 'CONFIRMED', 'NOT_REGISTERED'));

COMMENT ON COLUMN admission_reservation.consult_status IS
    '상담 진행 상태 6종. 재적 상태와 다른 축이다 — CONFIRMED(입학확정)에서만 학생으로 전환한다';

-- 상태 변경 이력.
-- ★ 없으면 "왜 미등록으로 바뀌었나" 에 답할 수 없다. created_by 는 등록 시점 작성자라
--   이후 변경자를 남기지 못한다 — enrollment_status_history 와 같은 이유다.
CREATE TABLE admission_reservation_status_log
(
    id             BIGSERIAL PRIMARY KEY,
    year           SMALLINT    NOT NULL,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    reservation_id BIGINT      NOT NULL REFERENCES admission_reservation (id),

    from_status    VARCHAR(20),
    to_status      VARCHAR(20) NOT NULL,
    reason         VARCHAR(500),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_admission_status_log_reservation
    ON admission_reservation_status_log (reservation_id, created_at DESC);

-- 상담 메모.
-- ★ 열람 범위는 SearchScope 가 판단한다 — 지점 메모는 그 지점만, 본사는 전체.
--   테이블에 별도 권한 컬럼을 두지 않는다(academy_id 로 충분하다).
CREATE TABLE admission_reservation_memo
(
    id             BIGSERIAL PRIMARY KEY,
    year           SMALLINT     NOT NULL,
    academy_id     BIGINT       NOT NULL REFERENCES academy (id),
    reservation_id BIGINT       NOT NULL REFERENCES admission_reservation (id),

    content        VARCHAR(2000) NOT NULL,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_admission_memo_reservation
    ON admission_reservation_memo (reservation_id, created_at DESC);

COMMENT ON TABLE admission_reservation_memo IS '입학 상담 메모. 지점 메모는 그 지점만, 본사는 전체를 본다';
