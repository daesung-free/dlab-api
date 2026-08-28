-- V20260828_1000: 입학예약 연동 (F-4.2-1 · D-7 · S-1)
--
-- DLab 홈페이지 입학예약창이 지금까지 대성전산 api.dshw.co.kr 을 호출해 왔다.
-- 그 자리에 우리가 앉는다 — 홈페이지는 주소만 바꾸고 코드는 그대로다.
-- 키오스크와 같은 구조이고, 계약은 `DSA_DLab_연동_정의서_v1.2`다.
--
-- ★ 입학예약은 student 가 아니다.
--   아직 학원생이 아니라 상담·심사 중인 지원자다. student_enrollment 에 넣으면
--   재원생 통계·키오스크 동기화·확정 배치에 전부 섞인다(직원 때와 같은 문제).
--   합격 처리 시점에 관리자가 학생으로 전환한다(F-4.1-4 신규 접수 등록).

-- ─────────────────────────────────────────────────────────────
-- 학원코드 매핑
--
-- ★ 홈페이지는 지점을 알파벳으로 보낸다(분당 F · 일산 I · 동탄 T …).
--   키오스크의 acad_cd(31·32·33…)와 다른 체계다 — 같은 지점을 부르는 이름이 둘이다.
--   지점당 값 하나라 별도 매핑 테이블은 과하고, 컬럼으로 붙인다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE academy ADD COLUMN IF NOT EXISTS dlab_cd VARCHAR(2);

CREATE UNIQUE INDEX uq_academy_dlab_cd
    ON academy (dlab_cd) WHERE dlab_cd IS NOT NULL;

COMMENT ON COLUMN academy.dlab_cd IS
    '홈페이지 입학예약 학원코드(F/I/T/P/C/E/J/O/A). 키오스크 acad_cd와 다른 체계다';

UPDATE academy SET dlab_cd = CASE acad_cd
    WHEN '31' THEN 'F'   -- 분당
    WHEN '32' THEN 'I'   -- 일산
    WHEN '33' THEN 'T'   -- 동탄
    WHEN '34' THEN 'P'   -- 김포
    WHEN '42' THEN 'C'   -- 부천
    WHEN '43' THEN 'E'   -- 이매
    WHEN '44' THEN 'J'   -- 광명
    WHEN '45' THEN 'O'   -- 목동
    WHEN '46' THEN 'A'   -- 송파
END
WHERE acad_cd IN ('31','32','33','34','42','43','44','45','46');

-- ─────────────────────────────────────────────────────────────
-- 입학예약
--
-- 필드는 규격서 3.3 그대로 받는다. 홈페이지가 보내는 것을 우리가 고를 수 없다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE admission_reservation (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,

    -- ★ 홈페이지에 돌려주는 학생고유코드(rsv_cd). 이후 성적·파일이 이 값으로 붙는다.
    --   id 를 그대로 쓰지 않는다 — 외부에 내보내는 식별자라 내부 순번이 드러나면
    --   지원자 수가 추정된다
    rsv_cd        VARCHAR(20) NOT NULL,

    student_name  VARCHAR(20) NOT NULL,
    student_tel   VARCHAR(20) NOT NULL,
    parent_tel    VARCHAR(20) NOT NULL,
    gender        VARCHAR(1)  CHECK (gender IN ('M','F')),
    birth         VARCHAR(8),
    -- 인문1 자연2 예체능3 공통9
    geyul_gb      SMALLINT,
    -- 등원 희망일
    adm_dt        VARCHAR(8),

    -- 공통코드 참조값. 우리가 값 목록을 아직 못 받아 코드만 보관한다
    pre_test      INTEGER,
    admi_st       INTEGER,
    find_gb       INTEGER,
    find_txt      VARCHAR(100),
    sch_cd        INTEGER,

    -- 내신: 일반고 91 / 특목·자사고 92
    nasin_st      SMALLINT,
    nasin_sc      NUMERIC(4,2),

    uni_nm        VARCHAR(50),
    uni_gd        SMALLINT,
    -- 면접: 성적 기준미달 81 / 기타 82
    intr_st       SMALLINT,
    intr_txt      VARCHAR(200),

    zip           VARCHAR(10),
    addr1         VARCHAR(200),
    addr2         VARCHAR(200),
    sch_cd_high   INTEGER,
    sch_nm_high   VARCHAR(50),

    agree_ad      BOOLEAN     NOT NULL DEFAULT FALSE,
    promo_ad      BOOLEAN     NOT NULL DEFAULT FALSE,

    -- 고1 1 / 고2 2 / 고3 3 / N수생 N
    std_grade     VARCHAR(1)  NOT NULL,

    -- 합격 처리로 학생이 되면 그 등록 건. 전환 전에는 NULL
    enrollment_id BIGINT      REFERENCES student_enrollment (id),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_admission_rsv_cd ON admission_reservation (rsv_cd);

-- 조회(3.4)가 지점·전형·이름·생년월일·연락처로 찾는다
CREATE INDEX idx_admission_lookup
    ON admission_reservation (academy_id, student_name, birth, student_tel)
    WHERE is_deleted = FALSE;

-- 관리자 대기자 목록
CREATE INDEX idx_admission_academy_year
    ON admission_reservation (academy_id, year, created_at DESC)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE admission_reservation IS
    '홈페이지 입학예약 신청(F-4.2-1). 아직 학원생이 아니라 student에 넣지 않는다';

-- ─────────────────────────────────────────────────────────────
-- 지원기준 성적 (3.7)
--
-- 과목마다 한 행이고 성적구분이 섞인다(백분위/등급/표준점수/원점수).
-- ★ 점수를 숫자로 받지 않는다 — 규격이 String 이고 등급·표준점수·백분위가
--   같은 칸에 들어온다. 숫자로 강제하면 나중에 형식이 하나만 달라도 저장이 막힌다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE admission_score (
    id             BIGSERIAL   PRIMARY KEY,
    reservation_id BIGINT      NOT NULL REFERENCES admission_reservation (id),

    -- B 백분위 · D 등급 · P 표준점수 · PB 표준점수+백분위 · W 원점수
    score_type     VARCHAR(2)  NOT NULL,
    subject        INTEGER     NOT NULL,
    score          VARCHAR(20) NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 예약의 같은 과목·구분은 한 벌이다. 홈페이지가 수정 후 다시 보내면 덮어쓴다
CREATE UNIQUE INDEX uq_admission_score
    ON admission_score (reservation_id, score_type, subject) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 성적표 파일 (3.8)
--
-- ★ 파일 본문을 DB에 넣지 않는다. Base64 성적표가 수 MB라 행이 부풀고
--   백업·복제 비용이 그대로 늘어난다. 저장 위치만 남기고 실제 바이트는
--   저장소 구현체가 가진다(지금은 로컬, 나중에 S3).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE admission_file (
    id             BIGSERIAL    PRIMARY KEY,
    reservation_id BIGINT       NOT NULL REFERENCES admission_reservation (id),

    content_type   VARCHAR(100),
    byte_size      BIGINT,
    storage_key    VARCHAR(300) NOT NULL,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_admission_file_reservation
    ON admission_file (reservation_id) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 공통코드 (3.5 · 3.6)
--
-- 홈페이지 드롭다운이 이걸로 그려진다 — 출신학원·지원기준·전형·알게된경로,
-- 그리고 과목 목록.
--
-- ⚠️ 실제 값 목록을 아직 못 받았다. DSA에 있던 데이터다.
--   테이블만 만들어두고 값은 받는 대로 넣는다 — 비어 있으면 홈페이지 드롭다운이
--   빈 채로 뜬다. penalty_rule 과 같은 방식이다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE common_code (
    id          BIGSERIAL   PRIMARY KEY,
    -- ACAD 출신학원 · ADMI 지원기준 · EXAM 전형 · FIND 알게된경로 · SUBJECT 과목
    grp         VARCHAR(20) NOT NULL,
    code        VARCHAR(20) NOT NULL,
    name        VARCHAR(100) NOT NULL,

    -- 과목만 쓴다. 국어1 수학2 영어3 탐구1=4 탐구2=5
    idx         SMALLINT,
    -- NULL이면 전 지점 공통. 지점마다 다른 항목(출신학원 등)이 있으면 값이 붙는다
    academy_id  BIGINT      REFERENCES academy (id),
    sort_order  SMALLINT    NOT NULL DEFAULT 0,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_common_code
    ON common_code (grp, code, COALESCE(academy_id, 0)) WHERE is_deleted = FALSE;

CREATE INDEX idx_common_code_group
    ON common_code (grp, sort_order) WHERE is_deleted = FALSE AND active = TRUE;

COMMENT ON TABLE common_code IS
    '홈페이지 입학예약 드롭다운 코드(3.5·3.6). 실제 값 목록은 대성전산 수령 대기';
