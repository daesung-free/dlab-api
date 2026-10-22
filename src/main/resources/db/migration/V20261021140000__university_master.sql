-- 대학·학과 마스터 (디랩 1021 피드백 6-1).
--
-- 디랩 원문: "같은 대학이라도 대학이름이나 학과 명, 계열을 다르게 입력할 수 있을 것
--            같습니다. 이런건 어떻게 통일되게 입력할 수 있을지 문의드립니다."
--
-- 지금 admission_result 의 대학·학과·계열이 전부 자유 문자열이고, 자동완성이
-- "이미 입력된 값"에서 만들어지므로 첫 오타가 그대로 후보가 된다.
--
-- 원본: md/전달/2026더프모지원대학학과코드_20260223.xlsx (4,921행)
--   열: 대학코드 · 대학명 · 계열코드 · 계열명 · 학과코드 · 학과명
--
-- ★★ PK 가 (대학코드, 학과코드) 다 — 학과코드만으로는 유일하지 않다.
--   실측: 학과코드 2,487개 중 503개가 둘 이상의 대학에서 쓰인다.
--
-- ★★ 학과명을 학과코드 사전으로 두지 말 것 — 같은 학과코드에 다른 이름이 있다.
--   실측 3건(표기 차이):
--     6274  명지대·단국대 「문예창작」      vs  추계예술대 「문예창작과」
--     7627  중앙대 「…-비실기」            vs  영산대 「…(비실기)」
--     7628  중앙대 「…-실기」              vs  영산대 「…(실기)」
--   그래서 학과명은 (대학,학과) 행에 둔다. 전달 문서는 "충돌 0건"이라고 했으나
--   파일을 직접 파싱해 보면 4건이 나온다.
--
-- ★ 계열은 학과코드마다 고정이다(충돌 0건) — 대학·학과를 고르면 계열이 따라온다.
--   계열을 따로 입력받지 말 것.
--
-- ⚠️ 코드는 문자열이다 — '001' 처럼 0으로 채워져 있다. 숫자로 읽으면 앞의 0이 날아간다.
-- ⚠️ 계열코드가 1·2·3·7 이다(4~6 없음). 연속이라고 가정하지 말 것.
-- ⚠️ 캠퍼스는 대학명에 들어 있다 — 「가천대(글로벌-성남)」·「연세대(미래-원주)」.
--   별도 캠퍼스 축이 필요 없다.

CREATE TABLE university
(
    id         BIGSERIAL PRIMARY KEY,
    year       SMALLINT    NOT NULL,
    code       VARCHAR(10) NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 연도별로 파일이 새로 온다(파일명이 2026더프모...20260223 이다).
--   같은 코드가 해마다 다른 이름일 수 있어 연도를 키에 넣는다.
CREATE UNIQUE INDEX uq_university_year_code
    ON university (year, code) WHERE is_deleted = FALSE;
CREATE INDEX ix_university_name ON university (year, name);

CREATE TABLE university_department
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT     NOT NULL,
    university_code VARCHAR(10)  NOT NULL,
    code            VARCHAR(10)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    track_code      VARCHAR(10),
    track_name      VARCHAR(50),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT       NOT NULL,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_university_department
    ON university_department (year, university_code, code) WHERE is_deleted = FALSE;
CREATE INDEX ix_university_department_name
    ON university_department (year, university_code, name);

COMMENT ON TABLE university IS
    '대학 마스터. 연도별로 파일이 새로 오므로 (year, code) 가 키다';
COMMENT ON COLUMN university.name IS
    '캠퍼스가 이름에 포함된다 — 「연세대(미래-원주)」. 별도 캠퍼스 축을 두지 말 것';
COMMENT ON TABLE university_department IS
    '학과 마스터. ★ 키가 (대학코드, 학과코드) 다 — 학과코드 503개가 여러 대학에서 쓰인다';
COMMENT ON COLUMN university_department.name IS
    '★ 학과코드 사전으로 쓰지 말 것 — 같은 코드에 다른 이름이 있다(표기 차이 3건 실측)';
COMMENT ON COLUMN university_department.track_name IS
    '계열. 학과코드마다 고정이라 대학·학과를 고르면 따라온다 — 따로 입력받지 말 것';

-- ★ 자유 입력 칸은 지우지 않는다.
--   디랩이 "이후 없는 대학/학과 추가 입력"을 요청했다 — 목록에 없는 대학을 직접
--   넣는 경로가 남아야 한다. 코드가 있으면 마스터 기준이고, 없으면 손으로 넣은 값이다.
ALTER TABLE admission_result
    ADD COLUMN university_code VARCHAR(10),
    ADD COLUMN department_code VARCHAR(10);

COMMENT ON COLUMN admission_result.university_code IS
    '마스터에서 고른 경우의 대학코드. NULL 이면 university_name 을 손으로 넣은 것이다';
COMMENT ON COLUMN admission_result.department_code IS
    '마스터에서 고른 경우의 학과코드. 대학코드와 함께 봐야 유일하다';
