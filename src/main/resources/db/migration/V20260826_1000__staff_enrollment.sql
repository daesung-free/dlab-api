-- V20260826_1000: 직원 카드 등록 + 근태 (신규 요구)
--
-- 지점 직원이 키오스크로 출퇴근을 찍는다. 카드·학번 발급과 키오스크 동기화가
-- 학생과 똑같이 필요해서 student_enrollment를 같이 쓴다 — 여기에 별도 테이블을 두면
-- 키오스크 동기화(getStdInfoList)를 union으로 짜야 하고, 카드 유니크도 두 테이블에
-- 걸쳐 관리하게 된다.
--
-- ★ 구분은 grade = 'STAFF' 하나로 한다. 불린 컬럼을 따로 두지 않는다 —
--   두 개면 어긋난 행(is_staff=true인데 grade=N_SU)이 생겼을 때 어느 쪽이 진실인지
--   판정할 방법이 없고, 둘 다 세팅하는 걸 빠뜨리는 경로가 반드시 나온다.
--   CHECK 제약이 값을 강제하고, 학년별 집계에서도 자동으로 갈린다.

ALTER TABLE student_enrollment DROP CONSTRAINT IF EXISTS student_enrollment_grade_check;

ALTER TABLE student_enrollment ADD CONSTRAINT student_enrollment_grade_check
    CHECK (grade IN ('HIGH2', 'HIGH3', 'N_SU', 'STAFF'));

COMMENT ON COLUMN student_enrollment.grade IS
    'HIGH2/HIGH3/N_SU 학생 · STAFF 직원(키오스크 출퇴근용). '
    '조회는 기본이 학생만이고 직원은 명시적으로 요청해야 나온다';

-- 학생 목록·확정 배치·통계가 전부 "현재 등록 건"을 훑는다. 거기서 직원을 빼는
-- 조건이 항상 붙으므로 부분 인덱스로 받쳐준다
CREATE INDEX idx_enrollment_current_student
    ON student_enrollment (academy_id, year)
    WHERE is_current = TRUE AND is_deleted = FALSE AND grade <> 'STAFF';

-- ─────────────────────────────────────────────────────────────
-- 근태
--
-- ★ 출결 원장(attendance_tagging_log)에 같이 쌓지 않는다.
--   같이 쌓으면 학생 출결 조회·통계·순공 계산에서 매번 직원을 걸러야 하고,
--   한 곳만 빠뜨려도 직원이 학생 통계에 조용히 섞인다.
--   무엇보다 근태는 노동법 영역이라 보존기간·열람권한이 출결과 다르게 간다.
--
-- ★ 출근/퇴근 2종만 받는다. 지각·조퇴 판정을 하지 않는다 —
--   그건 근무시간 마스터가 있어야 하는데 요구에 없다. 지금은 기록만 남긴다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE staff_attendance (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    work_date     DATE        NOT NULL,
    event_type    VARCHAR(10) NOT NULL CHECK (event_type IN ('IN', 'OUT')),
    recorded_at   TIMESTAMPTZ NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 출퇴근 토글이 "그날 마지막 기록"을 본다. 조회 화면도 같은 순서로 읽는다
CREATE INDEX idx_staff_attendance_daily
    ON staff_attendance (enrollment_id, work_date, recorded_at DESC)
    WHERE is_deleted = FALSE;

-- 관리자 조회 — 지점·기간
CREATE INDEX idx_staff_attendance_academy
    ON staff_attendance (academy_id, work_date)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE staff_attendance IS
    '직원 근태(키오스크 출퇴근). 학생 출결 원장과 분리한다 — 섞으면 학생 통계에서 매번 걸러야 하고 보존·열람 기준도 다르다';
COMMENT ON COLUMN staff_attendance.event_type IS
    'IN 출근 / OUT 퇴근. 그날 마지막 기록의 반대로 정해진다';
