-- ==========================================================================
-- 과정(course_type) · 전형(admission_type) · 기숙사(dorm)
--
-- 로드맵 Phase 2의 남은 기초 마스터. V1·V2에 없던 것들이다.
-- 파일명은 타임스탬프 규칙(CLAUDE.md §7) — 순번은 두 사람이 같은 번호를 만들어 충돌한다.
-- ==========================================================================


-- ==========================================================================
-- 1. 과정 (course_type)
-- ==========================================================================

-- 전년도 복사 의존순서에 이미 이름이 박혀 있다(CLAUDE.md §7 · entity-design §0-2):
--   department → course_type → class_group → curriculum → penalty_item → tuition
-- class_group(= class_master)보다 앞이라는 건 반이 과정을 참조한다는 뜻이다 — 아래 3번.
CREATE TABLE course_type (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    name       VARCHAR(50) NOT NULL,
    -- 정렬 순서. 이름순으로 두면 "종합반"이 "단과"보다 뒤로 가는 식으로 운영 감각과 어긋난다
    sort_order SMALLINT    NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    copied_from_id BIGINT  REFERENCES course_type (id),
    CONSTRAINT uq_course_type UNIQUE (academy_id, year, name)
);

COMMENT ON TABLE course_type IS '과정 마스터(종합반·단과 등). 전년도 복사 대상이고 class_master가 참조한다.';
COMMENT ON COLUMN course_type.copied_from_id IS '전년도 복사 원본. NULL이면 신규 생성분';


-- ==========================================================================
-- 2. 전형 (admission_type)
-- ==========================================================================

-- 검수 시나리오 S-4의 복사 순서 "학과→전형→반→…"에 나오는 그 전형이다.
-- 학생이 "어떤 전형으로 들어왔는가"라 student_enrollment가 참조한다(아래 4번).
CREATE TABLE admission_type (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    name       VARCHAR(50) NOT NULL,
    sort_order SMALLINT    NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    copied_from_id BIGINT  REFERENCES admission_type (id),
    CONSTRAINT uq_admission_type UNIQUE (academy_id, year, name)
);

COMMENT ON TABLE admission_type IS '전형 마스터. 전년도 복사 대상.';
COMMENT ON COLUMN admission_type.copied_from_id IS '전년도 복사 원본. NULL이면 신규 생성분';


-- ==========================================================================
-- 3. 반 → 과정 참조
-- ==========================================================================

-- NULL 허용이다. 이미 들어있는 반이 있고, 과정을 안 쓰는 지점도 있을 수 있다.
-- ★ 전년도 복사에서 이 FK를 새 연도 course_type으로 갈아끼워야 한다 —
--   그냥 복사하면 2027년 반이 2026년 과정을 가리켜, 옛 과정을 지우면 새 연도가 깨진다.
ALTER TABLE class_master ADD COLUMN course_type_id BIGINT REFERENCES course_type (id);

COMMENT ON COLUMN class_master.course_type_id IS
    '소속 과정. 전년도 복사 시 새 연도 course_type으로 갈아끼운다(단순 복사 금지).';


-- ==========================================================================
-- 4. 등록 건 → 전형 참조
-- ==========================================================================

-- 등록 건은 연도별로 새로 만들어지므로 전년도 복사 대상이 아니다 — 갈아끼울 일이 없다.
ALTER TABLE student_enrollment ADD COLUMN admission_type_id BIGINT REFERENCES admission_type (id);

COMMENT ON COLUMN student_enrollment.admission_type_id IS '입학 전형. 등록 건에 붙는다(그 해 입학 방식이므로).';


-- ==========================================================================
-- 5. 기숙사 (dorm_room · dorm_assignment)
-- ==========================================================================

-- ★ 사물함과 구조가 다르다. 사물함은 1칸에 1명이라 locker_master에 배정 컬럼을 직접 뒀지만,
--   기숙사는 한 방에 여러 명이 들어가므로 배정을 별도 테이블로 뺀다.
--   방에 배정 컬럼을 두면 정원만큼 컬럼을 만들거나 방을 인원수만큼 쪼개야 한다.
CREATE TABLE dorm_room (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    year       SMALLINT    NOT NULL,
    building   VARCHAR(30),
    room_no    VARCHAR(20) NOT NULL,
    capacity   SMALLINT    NOT NULL DEFAULT 2 CHECK (capacity > 0),
    gender     VARCHAR(1)  CHECK (gender IN ('M', 'F')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_dorm_room UNIQUE (academy_id, year, building, room_no)
);

COMMENT ON COLUMN dorm_room.gender IS '배정 가능 성별. NULL이면 제한 없음 — 남녀 혼숙 배정을 코드가 아니라 데이터로 막는다.';
COMMENT ON COLUMN dorm_room.capacity IS '정원. 초과 배정은 서비스가 막는다(DB로는 못 세므로).';

CREATE TABLE dorm_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    room_id       BIGINT      NOT NULL REFERENCES dorm_room (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL이면 현재 사용 중. 퇴실해도 행을 지우지 않는다 — 언제 누가 썼는지 남아야 한다
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);
-- year 없음 — enrollment_id가 이미 연도를 내포한다(entity-design §0-1).

-- ★ 한 학생이 동시에 두 방을 쓸 수 없다. 부분 유니크라 퇴실 이력은 얼마든지 쌓인다.
--   좌석 배정과 같은 패턴이고, 같은 함정도 있다 — 재배정 시 이전 건을 released 처리한 뒤
--   반드시 flush() 해야 한다(Hibernate가 INSERT를 UPDATE보다 먼저 내보낸다).
CREATE UNIQUE INDEX uq_dorm_assignment_active
    ON dorm_assignment (enrollment_id) WHERE released_at IS NULL AND is_deleted = FALSE;

CREATE INDEX idx_dorm_assignment_room ON dorm_assignment (room_id);
