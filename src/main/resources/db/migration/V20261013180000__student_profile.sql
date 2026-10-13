-- 신상기록부 (F-4.11-9).
--
-- 프론트는 화면을 완성해 뒀는데 **저장할 곳이 없었다** — 서버에 있던 것은
-- `ConsultStatusRow.profileWritten`(작성 여부 boolean) 하나였고 그것조차 늘 `null` 이었다.
--
-- ★★ **양식이 학년별로 갈린다(I-17 미확정).** 그래서 항목을 컬럼으로 박지 않는다 —
--    박으면 양식이 확정될 때마다 마이그레이션을 새로 쓰게 되고, 그때 **과거 학생이 무엇에
--    답했는지가 흐려진다**(`exam_master`/`exam_subject` 를 enum 대신 마스터로 둔 것과 같은 판단).
--    지금 화면에 있는 것(건강상태·생활습관·목표대학)만 칸으로 두고, 나머지는 양식 행 +
--    JSON 응답으로 흡수한다.

-- ─────────────────────────────────────────────────────────────
-- 1. 양식 — I-17 이 확정되면 **행만 넣는다**
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student_profile_form
(
    id          BIGSERIAL   PRIMARY KEY,
    year        SMALLINT    NOT NULL,

    -- NULL 이면 전 지점 공통. `holiday`·`terms` 와 같은 규약이다 —
    -- 지점별로 양식이 갈릴 수 있다고 0821 회신에서 확인됐다(장학 동의서와 같은 사정)
    academy_id  BIGINT      REFERENCES academy (id),

    -- NULL 이면 전 학년 공통. 신상기록부 실물이 예비고3 과 현고3 을 다른 양식으로 두는데
    -- **4종 매핑(I-17)이 미확정**이라, 확정 전에는 공통 한 행으로 쓰고 확정되면 학년별 행을 넣는다
    grade_type  VARCHAR(10) CHECK (grade_type IN ('HIGH2', 'HIGH3', 'N_SU')),

    name        VARCHAR(100) NOT NULL,

    -- 항목 정의. 화면이 이걸 읽어 입력칸을 그린다
    schema_json JSONB       NOT NULL,

    -- ★ 문구를 고치지 않고 **버전을 올려 새 행을 추가**한다(약관과 같은 판단).
    --   덮어쓰면 **이미 작성한 학생들이 무엇에 답했는지가 바뀐다** — 「흡연 여부」를
    --   「기저질환」으로 고치면 그 자리에 들어 있던 답이 다른 질문의 답으로 읽힌다
    version     SMALLINT    NOT NULL DEFAULT 1,

    -- ★ 기본값이 FALSE 다(`penalty_rule.active` 와 같은 판단). I-17 이 미확정이라
    --   행을 미리 넣어두게 되는데, 켜지 않은 양식이 앱에 떠서 학생이 쓰기 시작하면
    --   확정본과 어긋난 답이 쌓인다
    active      BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ NULL 을 COALESCE 로 접는다. 그냥 (year, academy_id, grade_type, version) 으로 걸면
--   PostgreSQL 이 NULL 을 서로 다른 값으로 보므로 **전 지점 공통 양식이 같은 버전으로
--   여러 번 들어간다**
CREATE UNIQUE INDEX uq_student_profile_form
    ON student_profile_form (year, COALESCE(academy_id, 0), COALESCE(grade_type, '*'), version)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE student_profile_form IS
    '신상기록부 양식. I-17 확정 전까지 공통 한 행으로 쓰고, 확정되면 학년별 행을 넣는다';
COMMENT ON COLUMN student_profile_form.active IS
    '기본 FALSE. 켜지 않은 양식은 쓰이지 않는다 — 미확정 양식이 실수로 도는 것을 막는다';

-- ─────────────────────────────────────────────────────────────
-- 2. 작성본
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student_profile
(
    id            BIGSERIAL   PRIMARY KEY,
    year          SMALLINT    NOT NULL,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),

    -- ★★ **사람(`student`)이 아니라 등록 건에 붙인다.** 학생이 다음 기수에 재등록하면
    --    그해 기록이 새 기수에 섞이면 안 된다(§3 2단 구조). 건강·생활습관은 1년 사이에
    --    실제로 바뀌고, 「작년에 쓴 것」이 올해 작성 완료로 보이면 담임이 다시 받지 않는다
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 어느 양식으로 썼나. ★ NULL 을 허용한다 — I-17 미확정이라 **양식 행 없이도 작성이
    -- 돌아야** 한다(지금 프론트 화면은 고정 항목이다). 확정 뒤 작성분만 채워진다
    form_id       BIGINT      REFERENCES student_profile_form (id),

    -- ── 건강상태 ──
    -- ⚠️ **민감정보다.** 수집 동의 문구가 아직 미확정이라, 저장 구조만 두고 **조회를
    --    상위 관리자로 제한**한다(`PersonalDataPolicy`). 동의 자체는 `terms`/`term_agreement`
    --    가 이력으로 관리하는 구조라 **여기 플래그로 두지 않는다** — 두 곳에 두면
    --    「그때 어떤 문구에 동의했나」의 답이 갈린다
    health_note   VARCHAR(2000),
    -- 알레르기·질환·복용약 등 선택 항목. 목록이 미확정이라 JSON 이다
    health_json   JSONB,

    -- ── 생활습관 (수면·식사·학습) ──
    life_note     VARCHAR(2000),
    life_json     JSONB,

    -- 양식(`form_id`)이 정의한 나머지 항목의 답. 양식이 바뀌어도 스키마를 안 고친다
    answers_json  JSONB,

    -- 최초 작성 시각. `created_at` 과 갈라 두는 이유는 **소급 입력**이다 —
    -- 종이로 받아둔 것을 데스크가 나중에 넣는 흐름이 실제로 있다(`effective_date` 와 같은 판단)
    written_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 등록 건마다 한 장이다. 두 장이면 상담 현황의 「작성 여부」가 어느 쪽을 보는지
--   정해지지 않고, 담임이 고친 내용이 다른 행에 남아 화면에서 사라진다
CREATE UNIQUE INDEX uq_student_profile_enrollment
    ON student_profile (enrollment_id)
    WHERE is_deleted = FALSE;

-- 상담 현황이 지점·연도 단위로 「누가 썼나」를 한 번에 읽는다
CREATE INDEX idx_student_profile_scope ON student_profile (academy_id, year);

COMMENT ON TABLE student_profile IS
    '신상기록부 작성본. 등록 건 단위 1장 — 재등록하면 새로 쓴다';
COMMENT ON COLUMN student_profile.health_json IS
    '민감정보. 조회는 상위 관리자만 — 수집 동의 문구 미확정';

-- ─────────────────────────────────────────────────────────────
-- 3. 목표대학 + 합격증
-- ─────────────────────────────────────────────────────────────
CREATE TABLE student_profile_university
(
    id                  BIGSERIAL    PRIMARY KEY,
    year                SMALLINT     NOT NULL,
    academy_id          BIGINT       NOT NULL REFERENCES academy (id),

    profile_id          BIGINT       NOT NULL REFERENCES student_profile (id),

    -- 화면의 줄 순서. 1지망·2지망이 순서로 표현된다 — 별 컬럼을 두면 같은 뜻이 두 곳에 생긴다
    sort_order          SMALLINT     NOT NULL DEFAULT 0,

    university_name     VARCHAR(100) NOT NULL,
    department_name     VARCHAR(100),

    -- ★ 전형은 **자유 문자열**이다. 전국 대학 전형명은 매년 바뀌어 마스터로 두면
    --   갱신 부담이 그대로 운영 부담이 된다(0821 회신에 그렇게 제안해 뒀다) —
    --   직원이 정리한 명칭이 다음 학생부터 자동완성으로 쌓이는 방향이다
    screening_name      VARCHAR(100),

    -- ★★ **합격증 파일은 `file_attachment` 의 id 만 참조한다.** 새 저장소를 만들지 않는다 —
    --    만들면 용량·형식 검증과 EXIF 제거, 만료 정리를 이 도메인에서 다시 짜게 되고
    --    한 곳만 빠뜨리면 그때부터 검증되지 않은 파일이 들어온다
    certificate_file_id BIGINT       REFERENCES file_attachment (id),

    -- 직원의 합불 확인(0821). ★ NULL 이 「아직 확인 안 함」이다 —
    -- FALSE 로 채우면 **불합격으로 읽혀 없는 사실이 생긴다**
    passed              BOOLEAN,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_student_profile_university
    ON student_profile_university (profile_id, sort_order);

COMMENT ON TABLE student_profile_university IS
    '목표대학. 합격증은 file_attachment 참조 — 저장소를 따로 두지 않는다';
COMMENT ON COLUMN student_profile_university.passed IS
    'NULL=미확인. FALSE 로 채우면 불합격으로 읽힌다';

-- ─────────────────────────────────────────────────────────────
-- 4. 메뉴
-- ─────────────────────────────────────────────────────────────
-- 계정별 메뉴 노출이 「화면 ↔ 메뉴 코드」 매핑으로 돌아간다. 빠뜨리면 조용히 두 가지로
-- 깨진다 — 빈 배열이면 아무에게도 안 보이고, 생략하면 누구에게도 안 감춰진다.
-- path_prefix 를 채워 **메뉴를 뺀 계정이 주소를 직접 쳐도 안 열리게** 한다.
INSERT INTO menu (code, name, parent_code, path_prefix, sort_order, action_only)
VALUES ('student-profile', '신상기록부', NULL, '/api/v1/admin/student-profiles', 83, FALSE)
ON CONFLICT (code) DO NOTHING;
