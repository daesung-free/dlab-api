-- V1: D.Lab 통합 플랫폼 초기 스키마 (전체)
--
-- 설계 근거: docs/entity-design.md
-- 아직 어디에도 적용되지 않은 상태라 단일 마이그레이션으로 둔다.
-- ★ 이 파일이 적용된 뒤의 스키마 변경은 반드시 V2 이상으로 추가할 것 — 이미 적용된
--   마이그레이션을 고치면 Flyway 체크섬이 깨져 다른 사람 환경이 기동하지 않는다.
--
-- 공통 컬럼 규칙(§0-1): id, academy_id, year, created_at, updated_at, created_by, is_deleted
--   ★ student_enrollment를 FK로 갖는 테이블은 year를 두지 않는다 — enrollment가 이미
--     연도를 내포하므로 중복이고, 둘이 어긋나면 어느 쪽이 맞는지 알 수 없게 된다.
--   ★ created_by(감사로그)·is_deleted(soft delete)는 나중에 붙이면 그 이전 기간의 이력이
--     영영 복구되지 않는다. 의도적 예외는 각 테이블에 사유를 주석으로 남겼다.
--
-- 목차
--   1. 지점(최소) · 학생(2단) · 학부모 · 선생님/직원 · 권한 · 반 · 교시 · 사물함
--   2. 공통 승인 라우팅  (사유신청 · 정기일정 · 방화벽 공용)
--   3. 출결  (태깅 원장 + 일자 집계 + 사유신청)
--   4. 방화벽 해제 + 상벌점
--   5. 알림  (이벤트-채널 매핑 + 템플릿 + 발송 로그)
--
-- ★ V2에서 다른 담당자가 만들 것: 지점 스키마 확정(ALTER) · 강의실/좌석/구역
--   레거시 미확보 영역이라 키오스크 계약에 맞춰 설계하기로 분담했다.


-- ==========================================================================
-- 1. 지점(최소) · 학생(2단) · 학부모 · 선생님/직원 · 권한 · 반 · 교시 · 사물함
-- ==========================================================================

--
-- 설계 근거: docs/entity-design.md §0, A, M, N
--
-- 공통 컬럼 규칙(§0-1): id, academy_id, year, created_at, updated_at, created_by, is_deleted
--   ★ 단 student_enrollment를 FK로 갖는 테이블은 year를 두지 않는다 — enrollment가 이미
--     연도를 내포하므로 중복이고, 둘이 어긋나면 어느 쪽이 맞는지 알 수 없게 된다.
--   의도적 예외는 각 테이블에 사유를 주석으로 남긴다.

-- ─────────────────────────────────────────────────────────────
-- 지점
-- ─────────────────────────────────────────────────────────────
CREATE TABLE academy (
    id                  BIGSERIAL    PRIMARY KEY,
    -- DSA 호환 getDlabList 대응. ★ 최소 형태다 — V2에서 키오스크 계약에 맞춰 확정한다.
    acad_cd             VARCHAR(20)  NOT NULL UNIQUE,
    acad_nm             VARCHAR(100) NOT NULL,
    -- 미등원 감지 배치 기준 시각. 학생별이 아니라 지점 공통이다.
    attendance_deadline TIME         NOT NULL DEFAULT '09:00',
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 자기 자신이 지점이고 연도에 종속되지 않는다(의도된 예외).
--
-- ★ 이 테이블만 V1에 남긴 이유: academy_id를 이 파일의 테이블 대부분이 FK로 참조한다.
--   V1에서 빼면 FK를 하나도 걸 수 없어 참조 무결성이 통째로 사라진다.
--   지점 스키마 자체는 레거시(DB_INFO.TB_ACADEMY_INFO) 미확보 상태이므로,
--   V2에서 키오스크 계약에 맞춰 ALTER TABLE로 컬럼을 채우면 된다
--   (full_nm 등 추가 필드, 필요 시 attendance_deadline 위치 조정 포함).

COMMENT ON COLUMN academy.attendance_deadline IS '등원 기준시각(지점 공통). 이 시각에 미등원 감지 배치가 돈다.';

-- ─────────────────────────────────────────────────────────────
-- 학생 = 사람 + 등록 건 2단 (§A1)
-- 학번·RFID가 사람이 아니라 등록 건에 붙는다 → 학번 매년 초기화가 구조적으로 보장되고,
-- 카드가 다음 기수에 재사용돼도 과거 기수 출결이 섞이지 않는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student (
    id                     BIGSERIAL   PRIMARY KEY,
    -- 학부모 자녀연결용 고유ID. 사람에 붙으므로 재등록해도 안 바뀐다.
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
-- academy_id/year 없음 — 사람 자체는 지점·연도 무관(의도된 예외, §0-1).

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

-- ─────────────────────────────────────────────────────────────
-- 학부모 (계정 1개 ↔ 자녀 N명, 자녀당 학부모는 최대 1인 — I-12 0803 확정)
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
    student_id     BIGINT      NOT NULL REFERENCES student (id),
    guardian_id    BIGINT      NOT NULL REFERENCES parent_guardian (id),
    relation_order SMALLINT    NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, guardian_id)
);
-- 사람(student)에 연결한다 — 자녀가 재등록해도 학부모 연결이 유지돼야 하기 때문.

CREATE INDEX idx_guardian_link_guardian ON student_guardian_link (guardian_id);

-- ─────────────────────────────────────────────────────────────
-- 선생님(강사) — ★ 담당선생님(사감)만 들어온다. 행정선생님은 employee 쪽이다.
--
-- 나누는 이유: 지점마다 강사 조직과 행정 조직이 실제로 분리되어 운영된다(2026-08-03 확인).
--   레거시도 TB_TEACHER_MST(PK: TCR_CD) / 직원관리_TB(PK: EMP_CD)로 나뉘어 있었고
--   합쳐서 관리한 흔적이 없었다 — 다만 이건 방증이지 근거가 아니다. 근거는 위 운영 실태다.
--
-- ⚠️ 합치고 싶어질 수 있는데(account 다형 FK가 3개로 줄어드니까) 합치지 말 것.
--   이 테이블에 담당선생님만 있기 때문에 class_master.homeroom_teacher_id와
--   approval_request.escalation_teacher_id의 FK가 곧 "담당선생님 보장"이 된다.
--   합치면 그 보장이 사라져 배정할 때마다 애플리케이션에서 역할을 검사해야 한다.
--
-- ※ 겸직(한 강사가 여러 지점)은 없다고 확인됐다(2026-08-03). 그래서 지점 귀속을
--   배정 테이블로 빼지 않고 academy_id를 직접 둔다.
-- ※ 담당선생님/행정선생님 구분도 여기 두지 않는다 — 요구사항정의서가 정본으로 정한
--   RBAC 5단계 role(TEACHER=담당선생님, STAFF=행정선생님)이 이미 담당하므로,
--   같은 것을 두 곳에서 표현하면 어긋났을 때 어느 쪽이 맞는지 알 수 없어진다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE teacher (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    name          VARCHAR(20) NOT NULL,
    phone         VARCHAR(20),
    email         VARCHAR(128),
    hired_date    DATE,
    resigned_date DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ※ 요구사항(F-4.10-2 "직원·강사 계정, 권한 관리, 지점별 접근 제어")에 필요한 만큼만 둔다.
--   레거시에 있던 본명·사진·사번·직급·강사료 계좌(REAL_NM/PHOTO/EMP_NO/LEVEL_NM/BANK_*)는
--   대응 요구사항이 없어 옮기지 않았다 — 레거시는 참고 사전이지 설계 기준이 아니다.
--   필요해지면 V2 이상에서 컬럼을 추가하면 된다(빼는 것보다 넣는 게 쉽다).

CREATE INDEX idx_teacher_academy ON teacher (academy_id);

-- ─────────────────────────────────────────────────────────────
-- 직원 — 행정 조직. ★ 행정선생님이 여기 들어온다(담당선생님은 teacher).
-- 겸직 케이스가 확인되지 않아 단일 지점을 유지한다.
--
-- ※ 공지(notice) 작성 권한이 두 테이블에 걸친다 — 전체공지는 행정선생님(employee),
--   반공지는 담당선생님(teacher). 그래서 notice를 만들 때 author를 다형 참조
--   (author_type CHECK IN ('TEACHER','EMPLOYEE') + author_id)로 둬야 한다.
-- 겸직이 필요해지면 teacher와 동일 패턴(배정 테이블)으로 전환할 것.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE employee (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    name          VARCHAR(20) NOT NULL,
    dept_name     VARCHAR(32),
    position_name VARCHAR(32),
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
-- 로그인 계정 (학생/학부모/직원/선생님 공통, 다형 FK)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE account (
    id            BIGSERIAL    PRIMARY KEY,
    account_type  VARCHAR(10)  NOT NULL
                  CHECK (account_type IN ('STUDENT', 'PARENT', 'EMPLOYEE', 'TEACHER')),
    -- 전화번호(학생·학부모) 또는 사번(직원·선생님)
    login_id      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    -- 학생만 PENDING을 거친다(승인 전 앱 접근 완전 차단). 학부모는 즉시 ACTIVE.
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    student_id    BIGINT       REFERENCES student (id),
    guardian_id   BIGINT       REFERENCES parent_guardian (id),
    employee_id   BIGINT       REFERENCES employee (id),
    teacher_id    BIGINT       REFERENCES teacher (id),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 넷 중 정확히 하나만 채워져야 한다
    CONSTRAINT ck_account_single_owner
        CHECK (num_nonnulls(student_id, guardian_id, employee_id, teacher_id) = 1)
);

CREATE UNIQUE INDEX uq_account_student ON account (student_id) WHERE student_id IS NOT NULL;
CREATE UNIQUE INDEX uq_account_guardian ON account (guardian_id) WHERE guardian_id IS NOT NULL;
CREATE UNIQUE INDEX uq_account_employee ON account (employee_id) WHERE employee_id IS NOT NULL;
CREATE UNIQUE INDEX uq_account_teacher ON account (teacher_id) WHERE teacher_id IS NOT NULL;
CREATE INDEX idx_account_status ON account (status);

-- ★ 학생 가입 승인(PENDING → ACTIVE)은 행정(employee)이 한다. 담당선생님이 아니다.
--    승인 화면은 관리자 웹이고, 승인자 기록은 created_by/감사로그로 남긴다.
--
-- ⚠️ 학생 재가입 중복 차단은 login_id UNIQUE만으로 부족하다.
--    PENDING 상태의 기존 승인요청까지 함께 검사해야 한다(승인 전이라도 이미 신청한 사람이다).
--    이 규칙은 신상기록부와 무관하다 — 회원가입 중복방지 로직이다.

-- ─────────────────────────────────────────────────────────────
-- 권한 — role은 account 기준으로 부여한다 (§N3)
-- teacher와 employee 양쪽에 걸칠 수 있어, 어느 엔티티인지 매번 분기하는 대신
-- 로그인 주체인 account에 붙인다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE role (
    id         BIGSERIAL   PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);
-- academy_id 없음 — role 자체는 지점 무관 개념(의도된 예외).
-- ★ 담당/행정 구분은 teacher_academy_assignment.duty_type이 이미 담당하므로 여기 넣지 않는다.

-- ★ RBAC 5단계는 요구사항정의서(정본) F-4.10-2에 명시된 값이다.
--   TEACHER=담당선생님(사감), STAFF=행정선생님 — 공지 작성 권한이 이 둘로 갈린다.
INSERT INTO role (name) VALUES
    ('SUPER_ADMIN'), ('BRANCH_ADMIN'), ('TEACHER'), ('STAFF'), ('READONLY');

CREATE TABLE account_role (
    account_id BIGINT      NOT NULL REFERENCES account (id),
    role_id    BIGINT      NOT NULL REFERENCES role (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role_id)
);

CREATE TABLE permission (
    id            BIGSERIAL   PRIMARY KEY,
    role_id       BIGINT      NOT NULL REFERENCES role (id),
    resource      VARCHAR(64) NOT NULL,
    action        VARCHAR(32) NOT NULL,
    -- SINGLE(소속 지점만) / ALL(전 지점). 전 지점 조회는 상위 관리자만.
    academy_scope VARCHAR(10) NOT NULL DEFAULT 'SINGLE' CHECK (academy_scope IN ('SINGLE', 'ALL')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_permission UNIQUE (role_id, resource, action)
);

-- ─────────────────────────────────────────────────────────────
-- 기초 마스터
-- ─────────────────────────────────────────────────────────────
CREATE TABLE department_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    name       VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_department_master UNIQUE (academy_id, year, name)
);

CREATE TABLE track_master (
    id         BIGSERIAL   PRIMARY KEY,
    name       VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 지점 무관 고정 참조값(의도된 예외).

-- 반 · 담임 (반배정에서 승인 에스컬레이션 대상이 자동 도출된다)
CREATE TABLE class_master (
    id                  BIGSERIAL   PRIMARY KEY,
    academy_id          BIGINT      NOT NULL REFERENCES academy (id),
    year                SMALLINT    NOT NULL,
    name                VARCHAR(50) NOT NULL,
    class_type          VARCHAR(10) NOT NULL DEFAULT 'FIXED' CHECK (class_type IN ('FIXED', 'MOVING')),
    -- 담임 = 담당선생님(사감). ★ employee가 아니라 teacher를 참조한다(§N).
    homeroom_teacher_id BIGINT      REFERENCES teacher (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_class_master UNIQUE (academy_id, year, name)
);

COMMENT ON COLUMN class_master.homeroom_teacher_id IS
    '담임(담당선생님). teacher 테이블에 담당선생님만 있으므로 이 FK가 곧 자격 보장이다. 반배정에서 승인 에스컬레이션 대상이 자동 도출된다.';

CREATE TABLE class_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    class_id      BIGINT      NOT NULL REFERENCES class_master (id),
    class_type    VARCHAR(10) NOT NULL DEFAULT 'FIXED' CHECK (class_type IN ('FIXED', 'MOVING')),
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);
-- year 없음 — enrollment_id가 이미 연도를 내포한다(§0-1).

CREATE UNIQUE INDEX uq_class_assignment_active
    ON class_assignment (enrollment_id, class_type) WHERE is_active = TRUE;
CREATE INDEX idx_class_assignment_class ON class_assignment (class_id);

-- ─────────────────────────────────────────────────────────────
-- 교시 마스터 — 출결 판정(code 113·122)과 학습계획 그리드가 같은 마스터를 쓴다.
-- 반마다 교시 시간이 갈리면 운영이 혼란해지므로 지점+연도 단위로만 둔다(0723 결정).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE period_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    period_no  SMALLINT    NOT NULL,
    name       VARCHAR(30),
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_period_master UNIQUE (academy_id, year, period_no)
);

-- ─────────────────────────────────────────────────────────────
-- 강의실 · 좌석 · 구역  →  ★ 이 V1에 없다. V2에서 별도 작성한다.
--
-- 레거시 좌석/구역 스키마(area_cd·xpos·ypos)가 미확보 상태라(§4), 추측으로 만들지 않고
-- DSA 호환 키오스크 계약(getStudyAreaSeatState 등)에 맞춰 설계하기로 했다.
-- 이 V1의 어떤 테이블도 좌석을 참조하지 않으므로 뒤에 붙여도 안전하다.
--
-- V2에서 만들 것: room_master · seat_master · seat_assignment(enrollment_id 참조)
--   그리고 좌석이탈 기록(seat_leave_record)은 스코프 자체가 미확정이라(I-16,
--   0803에 "앱 신청 경로 없음(조회 전용)"으로 확정됨) 아직 만들지 말 것.
-- ─────────────────────────────────────────────────────────────

-- 사물함은 좌석을 참조하지 않아 V1에 남긴다(배정 대상이 등록 건이므로).
CREATE TABLE locker_master (
    id                     BIGSERIAL   PRIMARY KEY,
    academy_id             BIGINT      NOT NULL REFERENCES academy (id),
    locker_no              VARCHAR(20) NOT NULL,
    -- 그 해 배정이므로 등록 건을 참조한다
    assigned_enrollment_id BIGINT      REFERENCES student_enrollment (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             BIGINT,
    is_deleted             BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_locker_master UNIQUE (academy_id, locker_no)
);

-- ─────────────────────────────────────────────────────────────
-- 장학
-- ─────────────────────────────────────────────────────────────
CREATE TABLE scholarship (
    id              BIGSERIAL     PRIMARY KEY,
    academy_id      BIGINT        NOT NULL REFERENCES academy (id),
    enrollment_id   BIGINT        NOT NULL REFERENCES student_enrollment (id),
    scholarship_type VARCHAR(20)  NOT NULL,
    discount_rate   NUMERIC(5, 2) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN       NOT NULL DEFAULT FALSE
);
-- year 없음 — enrollment_id가 이미 연도를 내포한다.

CREATE INDEX idx_scholarship_enrollment ON scholarship (enrollment_id);


-- ==========================================================================
-- 2. 공통 승인 라우팅
-- ==========================================================================

--
-- ※ approval_request에는 year 컬럼이 없다 — enrollment_id가 이미 연도를 내포하므로
--   중복이고, 둘이 어긋나면 어느 쪽이 맞는지 알 수 없게 된다(§0-1).
--
-- 설계 근거는 docs/entity-design.md E.
-- ★ firewall 안에 두지 않는다. 요구사항정의서 F-4.11-5는 사유신청·정기일정·방화벽을
--   하나의 라우팅 엔진에 태운다. firewall 안에 두면 사유신청에서 같은 로직을 또 짜게 된다.

CREATE TABLE approval_item (
    id                       BIGSERIAL   PRIMARY KEY,
    academy_id               BIGINT      NOT NULL REFERENCES academy (id),
    year                     SMALLINT    NOT NULL,
    request_type             VARCHAR(30) NOT NULL
                             CHECK (request_type IN ('FIREWALL_UNLOCK', 'ABSENCE_REASON', 'REGULAR_SCHEDULE')),
    -- 1차 승인 주체
    approver_type            VARCHAR(10) NOT NULL CHECK (approver_type IN ('PARENT', 'TEACHER', 'AUTO')),
    -- NULL이면 에스컬레이션 없음 (예: 정기일정은 학부모 단독 승인)
    timeout_minutes          SMALLINT,
    escalation_approver_type VARCHAR(10) CHECK (escalation_approver_type IN ('PARENT', 'TEACHER')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               BIGINT,
    is_deleted               BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_approval_item UNIQUE (academy_id, year, request_type),
    -- 타임아웃과 에스컬레이션 승인자는 같이 있거나 같이 없어야 한다
    CONSTRAINT ck_approval_escalation_pair
        CHECK ((timeout_minutes IS NULL) = (escalation_approver_type IS NULL))
);

COMMENT ON TABLE approval_item IS '승인 항목별 정책. 방화벽=PARENT→10분→TEACHER, 정기일정=PARENT 단독, 사유신청=TEACHER.';

CREATE TABLE approval_request (
    id                       BIGSERIAL   PRIMARY KEY,
    academy_id               BIGINT      NOT NULL REFERENCES academy (id),
    approval_item_id         BIGINT      NOT NULL REFERENCES approval_item (id),
    enrollment_id            BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 상태는 이 4종뿐. "타임아웃 후 승인됨"은 상태가 아니라 결과 속성이라
    -- resolution_case가 담당한다. 섞으면 "에스컬레이션됐지만 거절"을 표현할 수 없다.
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    requested_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ── 정책 스냅샷 (신청 시점 값을 박아둔다) ──
    -- 정책이 바뀌거나 담임이 교체돼도 이미 처리된 건의 이력이 흔들리면 안 된다.
    timeout_minutes          SMALLINT    NOT NULL,
    -- requested_at + timeout_minutes. 승인 케이스 판별의 기준선.
    escalation_at            TIMESTAMPTZ NOT NULL,
    -- 신청 시점의 담당선생님 스냅샷. class_assignment → class_master.homeroom_teacher_id로 자동 도출.
    -- ★ employee가 아니라 teacher를 참조한다 — 에스컬레이션 대상은 항상 담당선생님이다(§N).
    escalation_teacher_id    BIGINT      REFERENCES teacher (id),

    -- ── 처리 결과 ──
    resolved_at              TIMESTAMPTZ,
    resolver_type            VARCHAR(10) CHECK (resolver_type IN ('PARENT', 'TEACHER')),
    resolver_account_id      BIGINT      REFERENCES account (id),
    -- PARENT_IN_TIME       : 타임아웃 전 학부모 승인 (정상)
    -- STAFF_AFTER_TIMEOUT  : 무응답으로 타임아웃 경과 후 담당선생님 승인
    -- STAFF_BEFORE_TIMEOUT : 타임아웃 전인데 담당선생님이 먼저 승인
    -- ★ 뒤 두 개는 학부모에게 나가는 문구가 서로 달라야 한다. 절대 합치지 말 것.
    resolution_case          VARCHAR(30)
                             CHECK (resolution_case IN ('PARENT_IN_TIME', 'STAFF_AFTER_TIMEOUT', 'STAFF_BEFORE_TIMEOUT')),
    reject_reason            VARCHAR(500),

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               BIGINT,
    is_deleted               BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 학생이 같은 유형으로 동시에 여러 건을 올리지 못하게 막는다
CREATE UNIQUE INDEX uq_approval_request_pending
    ON approval_request (enrollment_id, approval_item_id) WHERE status = 'PENDING';
CREATE INDEX idx_approval_request_academy_status ON approval_request (academy_id, status);
-- 에스컬레이션 대상 조회용 (타임아웃 지난 대기 건)
CREATE INDEX idx_approval_request_escalation ON approval_request (escalation_at) WHERE status = 'PENDING';

COMMENT ON COLUMN approval_request.resolution_case IS
    'STAFF_AFTER_TIMEOUT과 STAFF_BEFORE_TIMEOUT은 학부모 안내 문구가 서로 달라야 한다.';
COMMENT ON COLUMN approval_request.escalation_at IS
    '승인 시각을 이 값과 비교해 resolution_case를 판별한다.';

-- 동시성 메모:
--   학부모 승인과 담당선생님 승인이 정확히 같은 순간 들어올 수 있다.
--   상태 전이는 조회 후 저장이 아니라 조건부 UPDATE(WHERE status='PENDING')로 처리하고,
--   갱신행이 0이면 이미 처리된 것으로 본다. 낙관적 락(version 컬럼)은 쓰지 않는다 —
--   두 방식을 섞으면 어느 쪽이 실제로 동시성을 막는지 불분명해진다.


-- ==========================================================================
-- 3. 출결 — 태깅 원장 + 일자 집계 + 사유신청
-- ==========================================================================

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
    enrollment_id       BIGINT       NOT NULL REFERENCES student_enrollment (id),
    attendance_date     DATE         NOT NULL,
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

CREATE INDEX idx_absence_reason_enrollment_date ON absence_reason (enrollment_id, attendance_date);
CREATE INDEX idx_absence_reason_academy_date ON absence_reason (academy_id, attendance_date);

-- ⚠️ "벌점 확정 후 사유 승인 불가"의 확정 기준은 미정(I-10) — 애플리케이션 레벨 처리.
-- ⚠️ 좌석이탈 기록 테이블은 스코프 자체가 미확정이라(I-16, 실시간 좌석표 2차 이관 제안 상태)
--    아직 만들지 않는다. 확정 후 별도 버전으로 추가할 것.


-- ==========================================================================
-- 4. 방화벽 해제 + 상벌점
-- ==========================================================================

--
-- 설계 근거는 docs/entity-design.md E', V.

-- ─────────────────────────────────────────────────────────────
-- 방화벽 해제 신청 — 승인 로직은 갖지 않는다. approval_request에 위임하고
-- 여기는 해제 자체의 고유 정보(시간·Zyxel)만 담는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE firewall_request (
    id                  BIGSERIAL   PRIMARY KEY,
    academy_id          BIGINT      NOT NULL REFERENCES academy (id),
    enrollment_id       BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 승인 상태·케이스는 전부 여기서 읽는다
    approval_request_id BIGINT      NOT NULL REFERENCES approval_request (id),
    requested_minutes   SMALLINT    NOT NULL CHECK (requested_minutes > 0 AND requested_minutes <= 300),
    reason              VARCHAR(500),
    unlock_start_at     TIMESTAMPTZ,
    unlock_end_at       TIMESTAMPTZ,
    -- Nebula API 호출 대상. ⚠️ 크레덴셜·제어 단위는 E-1 미해결.
    zyxel_site_id       VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_firewall_approval ON firewall_request (approval_request_id);
CREATE INDEX idx_firewall_enrollment ON firewall_request (enrollment_id);

COMMENT ON COLUMN firewall_request.requested_minutes IS '최대 300분(5시간).';

-- ─────────────────────────────────────────────────────────────
-- 상벌점 — ★ attendance 하위에 두지 않는다.
-- 규칙엔진은 출결과 데일리루틴 양쪽에서 트리거되므로(I-5),
-- attendance 아래 두면 daily_routine → attendance 순환 참조가 난다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE penalty_item (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,
    item_name   VARCHAR(100) NOT NULL,
    point_value INT         NOT NULL,
    category    VARCHAR(10) NOT NULL CHECK (category IN ('MERIT', 'DEMERIT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_penalty_item UNIQUE (academy_id, year, item_name)
);

COMMENT ON TABLE penalty_item IS '항목 마스터. 전년도 복사 대상 — 복사 의존순서는 docs/entity-design.md §0-2.';

CREATE TABLE penalty_rule (
    id                BIGSERIAL   PRIMARY KEY,
    academy_id        BIGINT      NOT NULL REFERENCES academy (id),
    year              SMALLINT    NOT NULL,
    trigger_type      VARCHAR(20) NOT NULL CHECK (trigger_type IN ('ATTENDANCE', 'DAILY_ROUTINE')),
    -- 트리거 조건. ⚠️ 매핑 규칙 자체가 미확정(I-5)이라 스키마만 두고 값은 비워둔다.
    trigger_condition VARCHAR(100) NOT NULL,
    penalty_item_id   BIGINT      NOT NULL REFERENCES penalty_item (id),
    active            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON COLUMN penalty_rule.active IS
    '기본 FALSE — 규칙 매핑(I-5)이 확정되기 전엔 자동 부여를 켜지 않는다. 수기 부여만 먼저 연다.';

CREATE TABLE penalty_point (
    id              BIGSERIAL    PRIMARY KEY,
    academy_id      BIGINT       NOT NULL REFERENCES academy (id),
    enrollment_id   BIGINT       NOT NULL REFERENCES student_enrollment (id),
    penalty_item_id BIGINT       NOT NULL REFERENCES penalty_item (id),
    points          INT          NOT NULL,
    reason          VARCHAR(500),
    source          VARCHAR(20)  NOT NULL CHECK (source IN ('KIOSK', 'ROUTINE', 'MANUAL')),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ★ 자동 부여 중복 방지. 예: 'ATTENDANCE:{enrollment_id}:{date}:{rule_id}'
    --   출결·루틴 이벤트가 중복 트리거되면 점수가 두 번 부여된다(키오스크 재태깅,
    --   배치 재실행, 다중 인스턴스). 애플리케이션 체크로는 동시 실행을 못 막으므로
    --   DB 유니크 제약으로 막는다. 수기 부여는 NULL.
    idempotency_key VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_penalty_idempotency
    ON penalty_point (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_penalty_enrollment ON penalty_point (enrollment_id, occurred_at DESC);

-- 방화벽 위반·제재 (상벌점과 연결되므로 penalty 뒤에 둔다)
CREATE TABLE firewall_violation (
    id                  BIGSERIAL   PRIMARY KEY,
    academy_id          BIGINT      NOT NULL REFERENCES academy (id),
    enrollment_id       BIGINT      NOT NULL REFERENCES student_enrollment (id),
    firewall_request_id BIGINT      REFERENCES firewall_request (id),
    penalty_point_id    BIGINT      REFERENCES penalty_point (id),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE firewall_restriction (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      NOT NULL REFERENCES academy (id),
    enrollment_id   BIGINT      NOT NULL REFERENCES student_enrollment (id),
    restricted_from TIMESTAMPTZ NOT NULL,
    restricted_until TIMESTAMPTZ NOT NULL,
    violation_count INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_firewall_restriction_active
    ON firewall_restriction (enrollment_id, restricted_until);

-- ⚠️ "2회 적발 시 2주 제한"의 누적 기준 기간(연간/학기/영구)이 미확인이다.
--    확정 전까지 violation_count 산정 로직을 고정하지 말 것.


-- ==========================================================================
-- 5. 알림 — 이벤트-채널 매핑 + 템플릿 + 발송 로그
-- ==========================================================================

--
-- 설계 근거는 docs/entity-design.md K.
-- 알림 "문구"는 미확정 블로커(I-4)다. 문구는 확정하지 않고 구조(이벤트 → 채널 매핑,
-- 변수 슬롯)만 만든다. content_confirmed = FALSE인 템플릿은 실제 발송하지 않는다.

CREATE TABLE notification_template (
    id                  BIGSERIAL    PRIMARY KEY,
    event_code          VARCHAR(60)  NOT NULL UNIQUE,
    -- 놓치면 안 되는 알림은 카카오 알림톡, 일반 알림은 FCM Push
    channel             VARCHAR(20)  NOT NULL CHECK (channel IN ('KAKAO_ALIMTALK', 'FCM_PUSH')),
    title_template      VARCHAR(200) NOT NULL DEFAULT '',
    body_template       TEXT         NOT NULL DEFAULT '',
    -- 렌더링 시 반드시 채워져야 하는 변수. 누락되면 발송하지 않고 실패시킨다.
    required_variables  TEXT         NOT NULL DEFAULT 'studentName',
    -- 카카오 알림톡은 사전심사(E-5)가 필요하므로 승인된 템플릿 코드를 보관
    kakao_template_code VARCHAR(60),
    -- FALSE면 아직 운영팀과 문구 협의 전이라는 뜻
    content_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);
-- academy_id/year 없음 — 템플릿은 전 지점 공통 참조값이다(의도된 예외).

CREATE TABLE notification_log (
    id                   BIGSERIAL    PRIMARY KEY,
    academy_id           BIGINT       REFERENCES academy (id),
    year                 SMALLINT,
    event_code           VARCHAR(60)  NOT NULL,
    channel              VARCHAR(20)  NOT NULL,
    recipient_account_id BIGINT       NOT NULL REFERENCES account (id),
    -- 어떤 학생에 대한 알림인지. 다자녀 학부모 구분에 필요.
    -- 사람(student)을 가리킨다 — 알림은 "누구 얘기인지"가 중요하지 기수가 중요하지 않다.
    student_id           BIGINT       REFERENCES student (id),
    title                VARCHAR(200) NOT NULL DEFAULT '',
    body                 TEXT         NOT NULL DEFAULT '',
    variables            JSONB,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    fail_reason          VARCHAR(500),
    sent_at              TIMESTAMPTZ,
    -- 중복 발송 방지 키. 예: 미등원 알림은 'MISSING_ATTENDANCE:{studentId}:{날짜}:{수신자}'.
    -- 배치가 재실행되거나 인스턴스가 여러 대여도 같은 알림이 두 번 나가지 않는다.
    dedup_key            VARCHAR(200),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_notification_dedup ON notification_log (dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX idx_notification_recipient ON notification_log (recipient_account_id, created_at DESC);
CREATE INDEX idx_notification_event ON notification_log (event_code, created_at DESC);

-- ─────────────────────────────────────────────────────────────
-- 이벤트 → 채널 매핑 (문구는 미확정이라 비워둔다)
-- 방화벽 승인 3케이스가 각각 다른 이벤트인 이유: 학부모에게 나가는 문구가 달라야 하기 때문.
-- ─────────────────────────────────────────────────────────────
INSERT INTO notification_template (event_code, channel, required_variables) VALUES
    ('MISSING_ATTENDANCE',               'KAKAO_ALIMTALK', 'studentName,attendanceDate'),
    ('APPROVAL_REQUEST_CREATED',         'KAKAO_ALIMTALK', 'studentName,requestedAt,timeoutMinutes'),
    ('APPROVAL_APPROVED_BY_PARENT',      'FCM_PUSH',       'studentName,resolvedAt'),
    ('APPROVAL_APPROVED_AFTER_TIMEOUT',  'KAKAO_ALIMTALK', 'studentName,resolvedAt,timeoutMinutes'),
    ('APPROVAL_APPROVED_BEFORE_TIMEOUT', 'KAKAO_ALIMTALK', 'studentName,resolvedAt'),
    ('APPROVAL_REJECTED',                'FCM_PUSH',       'studentName,resolvedAt');
