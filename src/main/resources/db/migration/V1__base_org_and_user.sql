-- V1: 지점 · 계정 · 학생(2단) · 학부모 · 직원 · 반 · 좌석/구역 · 교시 마스터
--
-- 설계 근거는 docs/entity-design.md §0, A, M.
-- 공통 컬럼 규칙(§0-1): id, academy_id, year, created_at, updated_at, created_by, is_deleted
--   created_by(감사로그)·is_deleted(soft delete)는 나중에 붙이면 그 이전 이력이 소실되므로 처음부터 넣는다.
--   의도적 예외는 각 테이블에 사유를 주석으로 남긴다.

-- ─────────────────────────────────────────────────────────────
-- 지점
-- ─────────────────────────────────────────────────────────────
CREATE TABLE academy (
    id                  BIGSERIAL    PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    -- 미등원 감지 배치 기준 시각. 학생별이 아니라 지점 공통이다.
    attendance_deadline TIME         NOT NULL DEFAULT '09:00',
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 자기 자신이 지점이고 연도에 종속되지 않는다(의도된 예외).

COMMENT ON COLUMN academy.attendance_deadline IS '등원 기준시각(지점 공통). 이 시각에 미등원 감지 배치가 돈다.';

-- ─────────────────────────────────────────────────────────────
-- 학생 = 사람 + 등록 건 2단 (docs/entity-design.md A1)
-- 학번·RFID가 사람이 아니라 등록 건에 붙는다 → 학번 매년 초기화가 구조적으로 보장되고,
-- 카드가 다음 기수에 재사용돼도 과거 기수 출결이 섞이지 않는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student (
    id                     BIGSERIAL   PRIMARY KEY,
    -- 학부모 자녀연결용 고유ID. 마이페이지 상시 노출. 사람에 붙으므로 재등록해도 안 바뀐다.
    unique_code            VARCHAR(20) NOT NULL UNIQUE,
    name                   VARCHAR(20) NOT NULL,
    phone                  VARCHAR(20),
    birth_date             DATE,
    gender                 VARCHAR(1)  CHECK (gender IN ('M', 'F')),
    school_name            VARCHAR(64),
    search_name_normalized VARCHAR(20),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             BIGINT,
    is_deleted             BOOLEAN     NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 지점과 연도는 등록 건(student_enrollment)의 속성이다(의도된 예외).

CREATE INDEX idx_student_search_name ON student (search_name_normalized);

COMMENT ON COLUMN student.unique_code IS '학생 고유ID. 이 값을 아는 사람이면 학부모 본인확인 없이 연결 가능(클라이언트가 인지·감수한 리스크).';

CREATE TABLE student_enrollment (
    id                BIGSERIAL   PRIMARY KEY,
    student_id        BIGINT      NOT NULL REFERENCES student (id),
    academy_id        BIGINT      NOT NULL REFERENCES academy (id),
    year              SMALLINT    NOT NULL,
    -- 학번. 매년 초기화되므로 PK·외부연동 키로 절대 쓰지 않는다.
    student_no        VARCHAR(20),
    -- 키오스크 카드 태깅 매칭 키. DSA 호환 엔드포인트 25개 중 13개가 이 값을 조회키로 쓴다.
    rfid_no           VARCHAR(10),
    -- 현재 유효한 등록 건 플래그 (레거시 STAT_GB='T' 대응)
    is_current        BOOLEAN     NOT NULL DEFAULT TRUE,
    grade             VARCHAR(10) NOT NULL CHECK (grade IN ('HIGH2', 'HIGH3', 'N_SU')),
    track             VARCHAR(10) CHECK (track IN ('HUMANITIES', 'SCIENCE', 'ART', 'COMMON')),
    enrollment_status VARCHAR(20) NOT NULL DEFAULT 'ENROLLED'
                      CHECK (enrollment_status IN ('ENROLLED', 'ON_LEAVE', 'WITHDRAWN', 'GRADUATED')),
    admission_date    DATE,
    withdrawal_date   DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_enrollment_student_no UNIQUE (academy_id, year, student_no)
);

-- ⚠️ rfid_no에 UNIQUE를 걸지 않는다. 등록 건마다 쌓이는 이력이기 때문이다.
--    카드번호로 학생을 찾을 때는 반드시 is_current = TRUE로 걸러야 한다.
--    이걸 빠뜨리면 퇴원생 카드로 태깅이 통과한다.
CREATE INDEX idx_enrollment_rfid_current ON student_enrollment (rfid_no) WHERE is_current = TRUE;
CREATE INDEX idx_enrollment_academy_year ON student_enrollment (academy_id, year);
CREATE INDEX idx_enrollment_student ON student_enrollment (student_id);

COMMENT ON COLUMN student_enrollment.rfid_no IS 'UNIQUE 아님(이력). 조회 시 is_current=TRUE 필수 — 누락 시 퇴원생 카드가 통과한다.';
COMMENT ON COLUMN student_enrollment.student_no IS '학번. 매년 초기화되므로 식별키로 사용 금지.';

-- ─────────────────────────────────────────────────────────────
-- 학부모 (계정 1개 ↔ 자녀 N명)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE parent_guardian (
    id         BIGSERIAL   PRIMARY KEY,
    name       VARCHAR(20) NOT NULL,
    phone      VARCHAR(20) NOT NULL UNIQUE,
    -- 부/모 구분 (DSA 호환 getParentHpList의 p_gb 대응)
    gender     VARCHAR(1)  CHECK (gender IN ('M', 'F')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);
-- academy_id 없음 — 학부모는 지점 종속이 아니라 자녀를 따라간다(의도된 예외).

CREATE TABLE student_guardian_link (
    student_id     BIGINT   NOT NULL REFERENCES student (id),
    guardian_id    BIGINT   NOT NULL REFERENCES parent_guardian (id),
    relation_order SMALLINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, guardian_id)
);
-- 사람(student)에 연결한다 — 자녀가 재등록해도 학부모 연결이 유지돼야 하기 때문.

CREATE INDEX idx_guardian_link_guardian ON student_guardian_link (guardian_id);

-- ─────────────────────────────────────────────────────────────
-- 직원
-- ⚠️ RBAC 축이 미확정이다(요구사항정의서는 5단계 권한등급, CLAUDE.md는 담당/행정 2종 직무구분).
--    확정 전까지 role/permission 테이블은 만들지 않고 employee_type만 둔다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE employee (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    name          VARCHAR(20) NOT NULL,
    -- HOMEROOM(담당선생님·사감) / ADMIN(행정선생님) / SUPER(상위 관리자)
    -- 공지 작성 권한이 이 구분에 따라 갈린다(전체공지=ADMIN, 반공지=HOMEROOM).
    employee_type VARCHAR(20) NOT NULL CHECK (employee_type IN ('HOMEROOM', 'ADMIN', 'SUPER')),
    dept_name     VARCHAR(64),
    position_name VARCHAR(64),
    phone         VARCHAR(20),
    email         VARCHAR(128),
    hired_date    DATE,
    resigned_date DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_employee_academy ON employee (academy_id);

-- ─────────────────────────────────────────────────────────────
-- 로그인 계정 (학생/학부모/직원 공통, 다형 FK)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE account (
    id            BIGSERIAL    PRIMARY KEY,
    account_type  VARCHAR(10)  NOT NULL CHECK (account_type IN ('STUDENT', 'PARENT', 'EMPLOYEE')),
    -- 전화번호(학생·학부모) 또는 사번(직원)
    login_id      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    -- 학생만 PENDING을 거친다(승인 전 앱 접근 완전 차단). 학부모는 즉시 ACTIVE.
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    student_id    BIGINT       REFERENCES student (id),
    guardian_id   BIGINT       REFERENCES parent_guardian (id),
    employee_id   BIGINT       REFERENCES employee (id),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 셋 중 정확히 하나만 채워져야 한다
    CONSTRAINT ck_account_single_owner CHECK (num_nonnulls(student_id, guardian_id, employee_id) = 1)
);

CREATE UNIQUE INDEX uq_account_student ON account (student_id) WHERE student_id IS NOT NULL;
CREATE UNIQUE INDEX uq_account_guardian ON account (guardian_id) WHERE guardian_id IS NOT NULL;
CREATE UNIQUE INDEX uq_account_employee ON account (employee_id) WHERE employee_id IS NOT NULL;
CREATE INDEX idx_account_status ON account (status);

-- ⚠️ 학생 재가입 중복 차단은 login_id UNIQUE만으로 부족하다.
--    PENDING 상태의 기존 승인요청까지 함께 검사해야 한다(승인 전이라도 이미 신청한 사람이다).
--    이 규칙은 신상기록부와 무관하다 — 회원가입 중복방지 로직이다.

-- ─────────────────────────────────────────────────────────────
-- 반 · 담임 (반배정에서 방화벽 에스컬레이션 승인자가 자동 도출된다)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE class_master (
    id                   BIGSERIAL   PRIMARY KEY,
    academy_id           BIGINT      NOT NULL REFERENCES academy (id),
    year                 SMALLINT    NOT NULL,
    name                 VARCHAR(50) NOT NULL,
    class_type           VARCHAR(10) NOT NULL DEFAULT 'FIXED' CHECK (class_type IN ('FIXED', 'MOVING')),
    -- 담임(담당선생님·사감). 승인 에스컬레이션 대상의 출처.
    homeroom_employee_id BIGINT      REFERENCES employee (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           BIGINT,
    is_deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_class_master UNIQUE (academy_id, year, name)
);

COMMENT ON COLUMN class_master.homeroom_employee_id IS '담임. 학생별 승인자 사전지정 UI를 만들지 않는 근거 — 반배정에서 자동 도출된다.';

CREATE TABLE class_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    class_id      BIGINT      NOT NULL REFERENCES class_master (id),
    class_type    VARCHAR(10) NOT NULL DEFAULT 'FIXED' CHECK (class_type IN ('FIXED', 'MOVING')),
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 이력 보존용. 배정이 바뀌면 이전 행을 FALSE로 내리고 새 행을 넣는다.
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 유형의 활성 배정은 등록 건당 1개
CREATE UNIQUE INDEX uq_class_assignment_active
    ON class_assignment (enrollment_id, class_type) WHERE is_active = TRUE;
CREATE INDEX idx_class_assignment_class ON class_assignment (class_id);

-- ─────────────────────────────────────────────────────────────
-- 교시 마스터 (출결 태깅·학습계획 그리드가 공유한다)
-- 반마다 교시 시간이 갈리면 운영이 혼란해지므로 지점+연도 단위로만 둔다(0723 결정).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE period_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    period_no  SMALLINT    NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_period_master UNIQUE (academy_id, year, period_no)
);

-- ─────────────────────────────────────────────────────────────
-- 구역 · 좌석 마스터
-- ⚠️ 레거시 구역·좌표(area_cd/xpos/ypos) 스키마가 미확보 상태다. 여기 좌표는 DSA 호환
--    엔드포인트(getStudyAreaSeatState 등)에서 역산한 최소 형태이고, 실물 수령 시 교차검증한다.
--    좌석이탈 기록 테이블은 스코프 자체가 미확정이라(I-16) 아직 만들지 않는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE area_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    name       VARCHAR(50) NOT NULL,
    area_code  VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_area_master UNIQUE (academy_id, area_code)
);

CREATE TABLE seat_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    area_id    BIGINT      NOT NULL REFERENCES area_master (id),
    seat_no    VARCHAR(20) NOT NULL,
    xpos       INT,
    ypos       INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_seat_master UNIQUE (area_id, seat_no)
);

-- 좌석 배정 (등록 건 단위 — 기수가 바뀌면 새로 배정된다)
CREATE TABLE seat_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    seat_id       BIGINT      NOT NULL REFERENCES seat_master (id),
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_seat_assignment_active
    ON seat_assignment (enrollment_id) WHERE is_active = TRUE;
CREATE UNIQUE INDEX uq_seat_occupied
    ON seat_assignment (seat_id) WHERE is_active = TRUE;
