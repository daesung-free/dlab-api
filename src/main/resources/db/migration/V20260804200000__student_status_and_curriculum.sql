-- ==========================================================================
-- 학생 상태 관리(F-4.1-8) + 커리큘럼(F-4.10-1)
--
-- 요구사항정의서 화면별 요구사항과 대조해 빠져 있던 두 가지를 채운다.
-- ==========================================================================


-- ==========================================================================
-- 1. 재적 상태 5종으로 맞춘다
-- ==========================================================================

-- 시트(F-4.1-8 · 3.데이터·업무로직 정의)가 정본이다:
--   재원(ENROLLED) → 휴원(LEAVE) → 재원 / 퇴원(WITHDRAWN) / 제적(EXPELLED) / 수료(GRADUATED)
--
-- V1은 4종이라 **제적(EXPELLED)이 통째로 빠져 있었다.** 화면명 자체가
-- "학생 상태 관리(재원·휴원·퇴원·제적)"이므로 없으면 그 화면이 성립하지 않는다.
-- 휴원도 ON_LEAVE로 넣었는데 시트 표기는 LEAVE라 맞춘다 — 운영 데이터가 없는 지금이 아니면
-- 나중엔 값 마이그레이션까지 필요해진다.
ALTER TABLE student_enrollment DROP CONSTRAINT IF EXISTS student_enrollment_enrollment_status_check;

UPDATE student_enrollment SET enrollment_status = 'LEAVE' WHERE enrollment_status = 'ON_LEAVE';

ALTER TABLE student_enrollment ADD CONSTRAINT student_enrollment_enrollment_status_check
    CHECK (enrollment_status IN ('ENROLLED', 'LEAVE', 'WITHDRAWN', 'EXPELLED', 'GRADUATED'));


-- ==========================================================================
-- 2. 상태 변경 이력
-- ==========================================================================

-- 시트의 student_status_logs(가칭). "언제 누가 왜 상태를 바꿨는가"가 남아야 한다 —
-- 등록 건의 현재 상태만 보면 퇴원 처리가 실수였는지 정당했는지 답할 수 없다.
CREATE TABLE student_status_log (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    -- 최초 등록분은 이전 상태가 없다
    from_status   VARCHAR(20),
    to_status     VARCHAR(20) NOT NULL
                  CHECK (to_status IN ('ENROLLED', 'LEAVE', 'WITHDRAWN', 'EXPELLED', 'GRADUATED')),
    reason        VARCHAR(200),
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);
-- year 없음 — enrollment_id가 이미 연도를 내포한다.

COMMENT ON TABLE student_status_log IS
    '재적 상태 전이 이력. 누가 바꿨는지는 created_by(SecurityAuditorAware)가 채운다.';

CREATE INDEX idx_student_status_log_enrollment ON student_status_log (enrollment_id, changed_at DESC);


-- ==========================================================================
-- 3. 커리큘럼
-- ==========================================================================

-- F-4.10-1 기초 관리의 데이터 항목에 `curriculums`가 있고, 전년도 복사 의존순서
--   department → course_type → class_group → curriculum → penalty_item → tuition
-- 에도 들어 있다. class_group 다음이라 **반을 참조**한다.
--
-- ⚠️ 시트에 필드 수준 정의가 없어 이름·순서·반 참조만 두는 최소 구성이다.
--    화면 요구사항이 구체화되면 컬럼을 더한다 — 억측으로 미리 넓히지 않는다.
CREATE TABLE curriculum (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    name           VARCHAR(100) NOT NULL,
    -- 반별 커리큘럼. NULL이면 지점 공통.
    -- ★ 전년도 복사에서 새 연도 반으로 갈아끼워야 한다(단순 복사 금지).
    class_id       BIGINT      REFERENCES class_master (id),
    sort_order     SMALLINT    NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    copied_from_id BIGINT      REFERENCES curriculum (id),
    CONSTRAINT uq_curriculum UNIQUE (academy_id, year, name)
);

COMMENT ON TABLE curriculum IS '커리큘럼 마스터. 전년도 복사 대상이고 반(class_master)을 참조한다.';
COMMENT ON COLUMN curriculum.class_id IS
    '소속 반. 전년도 복사 시 새 연도 class_master로 갈아끼운다 — 안 그러면 새 연도 커리큘럼이 옛 반을 가리킨다.';
COMMENT ON COLUMN curriculum.copied_from_id IS '전년도 복사 원본. NULL이면 신규 생성분';
