-- ==========================================================================
-- 화면 요구사항 대조 반영
--
-- 요구사항정의서 화면별 요구사항 + 프론트 화면(dlab-front)과 대조해 나온 차이를 맞춘다.
-- 목업에만 있고 시트 근거가 없는 항목은 넣지 않는다 — 목업도 확정본이 아니다.
-- ==========================================================================


-- ==========================================================================
-- 1. 전형(admission_type) 제거 — course_type과 같은 것이었다
-- ==========================================================================

-- ★ 내가 둘로 나눈 게 오독이었다.
--   시트 F-4.10-1은 화면명이 "기초 관리(학과·**전형**·반·강의실·사물함·장학)"이고
--   데이터 항목이 `departments, course_types, ...`다 — 즉 **전형 = course_types**.
--   프론트 BasicSettings도 course_type의 label이 '전형'이고 행이 재수정규/삼수이상/특별전형이다.
--   S-4의 "학과→전형→반"과 복사 의존순서의 "department→course_type→class_group"이
--   같은 순서를 가리키는 것을 다른 것으로 읽었다.
--
-- 운영 데이터가 없는 시점이라 물리 삭제한다.
ALTER TABLE student_enrollment DROP COLUMN IF EXISTS admission_type_id;
DROP TABLE IF EXISTS admission_type;


-- ==========================================================================
-- 2. 반 정원
-- ==========================================================================

-- 시트 F-4.1-5의 DSA 실사 근거: "고정반목록(계열/학과/담임/**정원**/원생수)".
-- 레거시에 실제로 있던 필드다.
-- NULL 허용 — 정원을 두지 않는 반이 있을 수 있고, 기존 행에 채울 값이 없다.
ALTER TABLE class_master ADD COLUMN capacity SMALLINT CHECK (capacity IS NULL OR capacity > 0);

COMMENT ON COLUMN class_master.capacity IS
    '정원. 초과 배정 여부는 서비스가 판단한다 — 반 배정은 정원을 넘겨야 하는 예외가 실제로 있다.';


-- ==========================================================================
-- 3. 주소
-- ==========================================================================

-- ★ 개인정보 규칙(실행가이드 3.2)이 "전화·**주소**·생년월일은 상위 관리자만 조회"라고
--   명시하는데 정작 주소 컬럼이 없었다. 규칙이 가리키는 대상이 스키마에 없던 셈이다.
--   신규 접수 화면에도 주소 입력란이 있다.
ALTER TABLE student ADD COLUMN address VARCHAR(200);

COMMENT ON COLUMN student.address IS
    '★ 민감 필드. 전화·생년월일과 같은 등급으로 다룬다 — 목록·엑셀에서 마스킹, 상위 관리자만 조회.';



-- ==========================================================================
-- 4. 교습비 마스터
-- ==========================================================================

-- 전년도 복사 의존순서의 마지막(`... → penalty_item → tuition`)이고
-- 검수 시나리오 S-4 통과 기준에도 들어 있다. 프론트 기초설정에도 tuitions로 있다.
--
-- ⚠️ 청구·수납 로직은 여기 없다. **마스터(금액 기준)만** 만든다 —
--    실제 청구는 PG 스펙(E-3)과 환불 산식(I-26)이 확정돼야 설계할 수 있다.
CREATE TABLE tuition (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    name           VARCHAR(100) NOT NULL,
    -- 원 단위. 소수점이 없으므로 정수로 둔다(금액에 부동소수를 쓰지 않는다).
    amount         INT         NOT NULL CHECK (amount >= 0),
    sort_order     SMALLINT    NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    copied_from_id BIGINT      REFERENCES tuition (id),
    CONSTRAINT uq_tuition UNIQUE (academy_id, year, name)
);

COMMENT ON TABLE tuition IS
    '교습비 마스터. 전년도 복사 대상(의존순서 마지막). 청구·수납 로직은 별도 — E-3·I-26 대기.';
COMMENT ON COLUMN tuition.amount IS '원 단위 정수. 금액에 부동소수를 쓰지 않는다.';
COMMENT ON COLUMN tuition.copied_from_id IS '전년도 복사 원본. NULL이면 신규 생성분';
