-- V3: 출결 — 태깅 원장 + 일자 집계 2단 + 사유신청
--
-- 설계 근거는 docs/entity-design.md C.
-- ★ 원장(log)과 일자 집계(status)를 분리한다. ABSENT(결석)는 "안 찍은 것"이라 태깅 로그에
--   남을 수 없고, 배치가 일자 단위로 확정하는 파생 상태값이다. 둘을 같은 enum에 섞으면
--   "결석 이벤트를 INSERT"하는 코드가 생긴다.

CREATE TABLE kiosk_device (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    location    VARCHAR(100),
    device_type VARCHAR(20) NOT NULL DEFAULT 'ATTENDANCE' CHECK (device_type IN ('ATTENDANCE', 'MEAL')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ─────────────────────────────────────────────────────────────
-- 태깅 원장 — 실제로 발생한 이벤트만
-- 이벤트 코드는 DSA 원본을 그대로 저장한다. 저장값이 곧 키오스크 응답값이라
-- 우리 식으로 바꾸면 호환 구획에서 매번 역매핑해야 하고, 하나만 틀려도 파싱이 깨진다.
--   S 등원 / T 하원 / A 지각 / D 외출 / N 사유외출 / C 조퇴 / R 복귀
-- 요구사항정의서 2시트의 5종(ON_TIME/LATE/ABSENT/OUT/EXCUSED)은 틀렸다 —
-- 하원·복귀가 빠져 있고, 하원 없이는 순공시간 계산이 불가능하다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE attendance_tagging_log (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    year            SMALLINT    NOT NULL,
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),
    event_type      VARCHAR(1)  NOT NULL CHECK (event_type IN ('S', 'T', 'A', 'D', 'N', 'C', 'R')),
    source          VARCHAR(20) NOT NULL DEFAULT 'KIOSK_NFC' CHECK (source IN ('KIOSK_NFC', 'APP_QR', 'MANUAL')),
    kiosk_device_id BIGINT      REFERENCES kiosk_device (id),
    period_id       BIGINT      REFERENCES period_master (id),
    recorded_at     TIMESTAMPTZ NOT NULL,
    -- recorded_at에서 뽑은 파생 컬럼. 미등원 배치·일별 집계가 전부 날짜 기준이라
    -- timestamptz를 매번 캐스팅하면 인덱스를 못 타고, 야간 자습 때문에 자정 경계도 애매해진다.
    attendance_date DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_tagging_enrollment_date ON attendance_tagging_log (enrollment_id, attendance_date);
CREATE INDEX idx_tagging_academy_date ON attendance_tagging_log (academy_id, attendance_date);

COMMENT ON COLUMN attendance_tagging_log.event_type IS
    'DSA 원본 코드 그대로: S등원 T하원 A지각 D외출 N사유외출 C조퇴 R복귀. ABSENT 없음(태깅 이벤트가 아님).';

-- ─────────────────────────────────────────────────────────────
-- 일자 집계 — 배치가 확정하는 파생 상태. ABSENT는 오직 여기에만 존재한다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE attendance_daily_status (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    year            SMALLINT    NOT NULL,
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),
    attendance_date DATE        NOT NULL,
    final_status    VARCHAR(20) NOT NULL
                    CHECK (final_status IN ('PRESENT', 'LATE', 'ABSENT', 'EARLY_LEAVE')),
    -- 순공시간(분). 하원(T) 태깅이 있어야 계산된다.
    study_minutes   INT,
    calculated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_daily_status UNIQUE (enrollment_id, attendance_date)
);

CREATE INDEX idx_daily_status_academy_date ON attendance_daily_status (academy_id, attendance_date);

-- ─────────────────────────────────────────────────────────────
-- 사전 제출 사유 — 승인 상태는 자체 컬럼이 아니라 approval_request가 갖는다.
-- 미등원 알림 배치는 반려되지 않은 사유가 있는 학생을 제외한다(무단결석만 대상).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE absence_reason (
    id                  BIGSERIAL    PRIMARY KEY,
    academy_id          BIGINT       NOT NULL REFERENCES academy (id),
    year                SMALLINT     NOT NULL,
    enrollment_id       BIGINT       NOT NULL REFERENCES student_enrollment (id),
    target_date         DATE         NOT NULL,
    reason_type         VARCHAR(20)  NOT NULL
                        CHECK (reason_type IN ('ABSENCE', 'LATE', 'EARLY_LEAVE', 'OUTING')),
    reason_text         VARCHAR(500) NOT NULL,
    submitted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 당일 자정
    deadline_at         TIMESTAMPTZ,
    -- 승인 주체는 관리자다(§5-1 "사유 승인/수정"은 관리자 웹 담당, 학부모 아님)
    approval_request_id BIGINT       REFERENCES approval_request (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_absence_reason_enrollment_date ON absence_reason (enrollment_id, target_date);
CREATE INDEX idx_absence_reason_academy_date ON absence_reason (academy_id, target_date);

-- ⚠️ "벌점 확정 후 사유 승인 불가"의 확정 기준은 미정(I-10) — 애플리케이션 레벨 처리.
-- ⚠️ 좌석이탈 기록 테이블은 스코프 자체가 미확정이라(I-16, 실시간 좌석표 2차 이관 제안 상태)
--    아직 만들지 않는다. 확정 후 별도 버전으로 추가할 것.
