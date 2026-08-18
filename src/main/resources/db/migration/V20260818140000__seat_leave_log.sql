-- V20260818140000: 좌석 이탈·복귀 로그 수신 (F-4.3-2)
--
-- 키오스크가 이탈/복귀가 생길 때마다 우리를 호출해 적재한다. 우리가 키오스크 DB를
-- 조회하는 방식은 채택하지 않았다 — 우리 서버가 키오스크 **스키마에 결합**되어
-- 그쪽 컬럼이 바뀌면 조용히 깨지고, 두 번째 데이터소스·자격증명·네트워크 경로가
-- 새로 필요해진다. 키오스크→우리 방향은 이미 25개가 돌고 있어 새로 만들 것이 없다.
--
-- ★ 유실은 "미전송분 재전송"으로 막는다. 키오스크가 이미 seat_leaves를 자기 DB에
--   들고 있으므로, 응답 200을 못 받은 행을 다시 보내면 된다 — 타이밍은 즉시(push)인데
--   의미는 pull(유실 없음)이 된다.
--
-- ⚠️ 이 마이그레이션은 **적재까지만** 만든다. 장시간 미복귀 감지 스케줄러는
--    임계값(I-16)과 벌점 트리거 1차/2차 여부(I-5)가 미확정이라 착수하지 않았다.
--    임계값을 모르는 채로 스케줄러를 짜면 기본값이 그대로 운영에 굳는다.

CREATE TABLE seat_leave_log (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,

    -- ★ 키오스크 쪽 행 ID. 재전송 중복을 거르는 유일한 수단이다.
    --   이게 없으면 "같은 학생이 같은 초에 두 번 이탈"을 중복으로 볼지 실제 두 건으로
    --   볼지 판정할 수 없다.
    source_row_id BIGINT      NOT NULL,

    -- ★ 학생을 못 찾아도 행은 남긴다(NULL 허용).
    --   못 찾았다고 거절하면 키오스크가 그 행을 영원히 재전송하며 큐가 안 빠지고,
    --   기록 자체도 사라져 나중에 원인을 찾을 수 없다. 원본 식별자를 함께 보관해
    --   나중에 이어 붙일 수 있게 한다.
    enrollment_id BIGINT      REFERENCES student_enrollment (id),
    rfid_no       VARCHAR(50),
    std_no        VARCHAR(20),

    -- 좌석·구역은 키오스크가 주는 코드 그대로 둔다. 우리 seat 마스터와 조인하지 않는다 —
    -- 컷오버 전에는 양쪽 좌석 코드가 어긋날 수 있고, 그때 적재가 실패하면 안 된다
    area_cd       VARCHAR(20),
    seat_cd       VARCHAR(20),

    event_type    VARCHAR(10) NOT NULL CHECK (event_type IN ('LEAVE','RETURN')),
    occurred_at   TIMESTAMPTZ NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 멱등의 핵심. 같은 지점의 같은 행 ID는 한 번만 들어간다.
--   재전송이 몇 번 들어와도 한 줄만 남는다.
--   soft delete 조건을 걸지 않는다 — 지운 뒤 같은 행이 다시 들어오면 되살아난 것처럼
--   보이는데, 이건 이력이라 지울 일 자체가 없다.
CREATE UNIQUE INDEX uq_seat_leave_log_source
    ON seat_leave_log (academy_id, source_row_id);

-- 미복귀 감지가 "이탈 후 복귀가 없는 건"을 학생·시각으로 찾는다
CREATE INDEX idx_seat_leave_log_lookup
    ON seat_leave_log (enrollment_id, occurred_at) WHERE is_deleted = FALSE;

-- 학생을 못 찾은 행을 나중에 이어 붙이기 위한 조회
CREATE INDEX idx_seat_leave_log_unresolved
    ON seat_leave_log (academy_id, occurred_at)
    WHERE enrollment_id IS NULL AND is_deleted = FALSE;

COMMENT ON COLUMN seat_leave_log.source_row_id IS
    '키오스크 seat_leaves 행 ID. (academy_id, source_row_id)가 멱등키다';
COMMENT ON COLUMN seat_leave_log.enrollment_id IS
    'NULL이면 학생을 못 찾은 행. 거절하지 않고 원본 식별자와 함께 남긴다';
