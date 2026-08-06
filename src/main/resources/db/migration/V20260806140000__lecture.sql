-- ==========================================================================
-- 특강 관리 (F-4.10-4 기초설정 · F-4.7 관리 · 앱 A-15)
--
--   lecture              특강·설명회 마스터
--   lecture_session      회차 — 출석부가 회차 단위라 필요하다
--   lecture_application  신청 (정원 초과 시 대기)
--   lecture_attendance   회차별 출석
--
-- ⚠️ 결제는 넣지 않는다. 0803 답변서가 *"특강 신청+결제 가능 여부 검토 중"*이고
--    payment 도메인 자체가 아직 없다. 금액은 안내용으로만 보관한다.
-- ==========================================================================


-- ==========================================================================
-- 1. 특강 마스터
-- ==========================================================================

-- ★ 특강과 설명회를 한 테이블에 둔다.
--   F-4.10-4가 *"특강·설명회 기초 설정"*으로 묶어놨고, 둘 다 "열고 → 신청받고 →
--   명단 관리"라는 같은 흐름이다. 테이블을 나누면 신청·대기자·출석을 두 벌씩 만들게 된다.
CREATE TABLE lecture (
    id            BIGSERIAL    PRIMARY KEY,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),
    year          SMALLINT     NOT NULL,
    lecture_type  VARCHAR(20)  NOT NULL DEFAULT 'LECTURE'
                  CHECK (lecture_type IN ('LECTURE', 'BRIEFING')),
    name          VARCHAR(100) NOT NULL,
    description   TEXT,
    -- ▷[0803] "특강 노출 설정 — 개설 시에만 노출". 앱 목록에서 빼는 스위치다.
    -- ★ status와 다른 축이다: 접수를 닫아도(CLOSED) 목록에는 보여야 하는 경우가 있고,
    --   반대로 준비 중인 특강을 접수 열기 전에 숨겨야 하는 경우도 있다.
    visible       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- DSA 실사에 "상태별 필터"가 있다.
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'DONE', 'CANCELED')),
    -- 정원. NULL이면 무제한 — 설명회는 정원을 안 두는 경우가 있다.
    capacity      INTEGER      CHECK (capacity IS NULL OR capacity > 0),
    -- 접수 기간. 밖에서는 신청이 거절된다.
    apply_from    TIMESTAMPTZ,
    apply_to      TIMESTAMPTZ,
    -- 특강 진행 기간(안내용). 실제 일정은 lecture_session이 갖는다.
    start_date    DATE,
    end_date      DATE,
    -- ⚠️ 안내용 금액이다. 결제 연동이 없으므로 이 값으로 수납이 일어나지 않는다.
    fee           INTEGER      NOT NULL DEFAULT 0 CHECK (fee >= 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE lecture IS '특강·설명회 마스터. lecture_type으로 구분한다 — 흐름이 같아 테이블을 나누지 않는다.';
COMMENT ON COLUMN lecture.visible IS
    '앱 노출 여부(0803 "개설 시에만 노출"). status와 다른 축 — 접수를 닫아도 목록에는 보일 수 있다.';
COMMENT ON COLUMN lecture.fee IS '안내용 금액. 결제 연동 없음(0803 "신청+결제 검토 중").';

CREATE INDEX idx_lecture_academy ON lecture (academy_id, year, status) WHERE is_deleted = FALSE;


-- ==========================================================================
-- 2. 회차
-- ==========================================================================

-- 출석부(클라이언트가 DSA 기능확인에서 "특강출석부"를 사용으로 직접 추가했다)가
-- 회차 단위라 필요하다. 특강이 하루짜리면 회차 1개다.
CREATE TABLE lecture_session (
    id           BIGSERIAL   PRIMARY KEY,
    academy_id   BIGINT      NOT NULL REFERENCES academy (id),
    year         SMALLINT    NOT NULL,
    lecture_id   BIGINT      NOT NULL REFERENCES lecture (id),
    session_no   SMALLINT    NOT NULL,
    session_date DATE        NOT NULL,
    start_time   TIME,
    end_time     TIME,
    room         VARCHAR(50),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_lecture_session UNIQUE (lecture_id, session_no)
);

CREATE INDEX idx_lecture_session_lecture ON lecture_session (lecture_id, session_no);


-- ==========================================================================
-- 3. 신청
-- ==========================================================================

-- ★ 대기자를 별도 테이블로 만들지 않는다.
--   정원이 차면 같은 행이 WAITLISTED로 들어가고, 자리가 나면 APPLIED로 승격된다.
--   테이블을 나누면 "대기 → 확정" 전환 때 행을 옮겨야 하고, 그 과정에서
--   신청 시각(= 대기 순번의 근거)을 잃기 쉽다.
--
-- ⚠️ 여기의 "대기자"는 F-4.2 입학 대기자와 <b>다른 것</b>이다.
--    시트도 *"DSA '특강관리>대기자 접수'는 대상이 다름(특강 대기자)"*이라 명시했다.
CREATE TABLE lecture_application (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    lecture_id    BIGINT      NOT NULL REFERENCES lecture (id),
    -- 등록 건에 붙인다 — 학번·지점·학년이 여기 있고, 특강은 그 해 안에서만 유효하다.
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    status        VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
                  CHECK (status IN ('APPLIED', 'WAITLISTED', 'CANCELED')),
    applied_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    canceled_at   TIMESTAMPTZ,
    -- 관리자 메모(일괄 이동·수정 시 사유)
    memo          VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 한 학생이 같은 특강에 두 번 신청할 수 없다. 단 <b>취소분은 제외</b>한다 —
--   취소 후 재신청이 실무에서 흔한데, 전부 막으면 한 번 취소한 학생이 영영 못 넣는다.
CREATE UNIQUE INDEX uq_lecture_application
    ON lecture_application (lecture_id, enrollment_id)
 WHERE status <> 'CANCELED' AND is_deleted = FALSE;

COMMENT ON TABLE lecture_application IS
    '특강 신청. 정원 초과 시 같은 행이 WAITLISTED로 들어간다 — 대기자 테이블을 따로 두지 않는다.';
COMMENT ON INDEX uq_lecture_application IS
    '중복 신청 방지. 취소분은 제외해 재신청이 가능하다.';

-- 대기 순번은 신청 시각 순이다. 별도 컬럼을 두지 않는 이유는 앞사람이 취소할 때마다
-- 뒤 번호를 전부 다시 써야 하기 때문이다(그 사이 신규 신청이 끼면 순번이 어긋난다).
CREATE INDEX idx_lecture_application_lecture
    ON lecture_application (lecture_id, status, applied_at);
CREATE INDEX idx_lecture_application_enrollment
    ON lecture_application (enrollment_id, status);


-- ==========================================================================
-- 4. 출석
-- ==========================================================================

CREATE TABLE lecture_attendance (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    session_id     BIGINT      NOT NULL REFERENCES lecture_session (id),
    application_id BIGINT      NOT NULL REFERENCES lecture_application (id),
    -- 출결 원장(7종)과 다른 체계다 — 특강 출석부는 참석/결석/지각 3종이면 충분하고,
    -- 키오스크 태깅이 아니라 강사가 손으로 체크한다.
    status         VARCHAR(20) NOT NULL DEFAULT 'PRESENT'
                   CHECK (status IN ('PRESENT', 'ABSENT', 'LATE')),
    memo           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_lecture_attendance UNIQUE (session_id, application_id)
);

COMMENT ON TABLE lecture_attendance IS
    '특강 회차별 출석. 출결 원장(7종)과 별개다 — 강사가 손으로 체크하는 출석부다.';
