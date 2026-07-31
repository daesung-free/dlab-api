-- V1: 지점 · 반 · 계정(학생/학부모/직원) 기반 스키마
-- 참고: CLAUDE.md §3 대전제
--  - 학번(student_no)은 매년 초기화되므로 PK/외부연동키로 쓰지 않는다. 내부 PK는 BIGSERIAL.
--  - 모든 테이블에 branch_id 계열 컬럼을 둔다(다지점 구조).
--  - 학부모는 계정 1개에 자녀 여러 명을 연결한다(별도 계정 분리 아님).

-- ─────────────────────────────────────────────────────────────
-- 지점
-- ─────────────────────────────────────────────────────────────
CREATE TABLE branch (
    id                  BIGSERIAL    PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    -- 미등원 감지 배치 기준 시각. 학생별이 아니라 지점 공통이다(CLAUDE.md §3).
    attendance_deadline TIME         NOT NULL DEFAULT '09:00',
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN branch.attendance_deadline IS '등원 기준시각(지점 공통). 이 시각에 미등원 감지 배치가 돈다.';

-- ─────────────────────────────────────────────────────────────
-- 로그인 계정 (학생/학부모/직원 공통)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE user_account (
    id            BIGSERIAL    PRIMARY KEY,
    -- 휴대폰 인증 기반 가입이므로 휴대폰번호가 로그인 식별자
    phone         VARCHAR(20)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    -- STUDENT / PARENT / STAFF_HOMEROOM(담당선생님·사감) / STAFF_ADMIN(행정선생님) / SUPER_ADMIN
    role          VARCHAR(30)  NOT NULL,
    -- PENDING(학생 승인대기) / ACTIVE / SUSPENDED
    -- 학생만 PENDING을 사용한다. 학부모는 즉시 ACTIVE(CLAUDE.md §3).
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- SUPER_ADMIN은 전 지점 접근이므로 NULL 허용
    branch_id     BIGINT       REFERENCES branch (id),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_account_branch_role ON user_account (branch_id, role);
CREATE INDEX idx_user_account_status ON user_account (status);

-- ─────────────────────────────────────────────────────────────
-- 직원 (담당선생님(사감) / 행정선생님)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE staff (
    id              BIGSERIAL   PRIMARY KEY,
    user_account_id BIGINT      NOT NULL UNIQUE REFERENCES user_account (id),
    branch_id       BIGINT      NOT NULL REFERENCES branch (id),
    -- HOMEROOM(담당선생님·사감) / ADMIN(행정선생님)
    -- 공지 작성 권한이 이 구분에 따라 갈린다(전체공지=ADMIN, 반공지=HOMEROOM).
    staff_type      VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_branch ON staff (branch_id);

-- ─────────────────────────────────────────────────────────────
-- 반 (담임 = 담당선생님(사감))
-- 학생을 반에 배정하면 방화벽 에스컬레이션 승인자가 자동으로 정해진다(CLAUDE.md §3).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE school_class (
    id                 BIGSERIAL   PRIMARY KEY,
    branch_id          BIGINT      NOT NULL REFERENCES branch (id),
    name               VARCHAR(50) NOT NULL,
    school_year        INT         NOT NULL,
    homeroom_staff_id  BIGINT      REFERENCES staff (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_school_class UNIQUE (branch_id, school_year, name)
);

COMMENT ON COLUMN school_class.homeroom_staff_id IS '담임(담당선생님·사감). 방화벽 에스컬레이션 승인자의 출처.';

-- ─────────────────────────────────────────────────────────────
-- 학생
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student (
    id              BIGSERIAL   PRIMARY KEY,
    user_account_id BIGINT      NOT NULL UNIQUE REFERENCES user_account (id),
    branch_id       BIGINT      NOT NULL REFERENCES branch (id),
    class_id        BIGINT      REFERENCES school_class (id),
    -- 학부모가 자녀 연결 시 입력하는 값. 마이페이지에 상시 노출된다(CLAUDE.md §3).
    public_code     VARCHAR(20) NOT NULL UNIQUE,
    -- 학번은 매년 초기화되므로 (지점, 학년도) 안에서만 유일하다. 절대 식별키로 쓰지 말 것.
    student_no      VARCHAR(20),
    school_year     INT         NOT NULL,
    -- HIGH2(고2) / HIGH3(고3) / N_SU(n수생) — 기획서의 "성인"은 n수생을 의미
    grade_type      VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_no UNIQUE (branch_id, school_year, student_no)
);

CREATE INDEX idx_student_branch_class ON student (branch_id, class_id);

COMMENT ON COLUMN student.public_code IS '학부모 자녀연결용 고유ID. 본인확인 없이 이 값만으로 연결 가능(클라이언트가 감수한 리스크).';
COMMENT ON COLUMN student.student_no IS '학번. 매년 초기화되므로 식별키로 사용 금지.';

-- ─────────────────────────────────────────────────────────────
-- 학부모 (계정 1개 ↔ 자녀 N명)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE parent (
    id              BIGSERIAL   PRIMARY KEY,
    user_account_id BIGINT      NOT NULL UNIQUE REFERENCES user_account (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE parent_student (
    id         BIGSERIAL   PRIMARY KEY,
    parent_id  BIGINT      NOT NULL REFERENCES parent (id),
    student_id BIGINT      NOT NULL REFERENCES student (id),
    -- 앱의 자녀전환 UI에서 기본으로 보여줄 자녀
    is_primary BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_parent_student UNIQUE (parent_id, student_id)
);

CREATE INDEX idx_parent_student_student ON parent_student (student_id);
