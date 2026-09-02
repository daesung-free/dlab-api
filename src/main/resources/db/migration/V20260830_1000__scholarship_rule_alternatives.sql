-- V20260830_1000: 장학 취소 기준 — 장학 등급별 · OR 대안 · AND 조건 · 탐구 집계
--
-- ★ 왜 고치는가: 0826 답변서 기준표를 지금 테이블로 표현할 수 없다.
--
--   수능 100%   (국+수+탐2평균) ≤ 4  또는  (국+수+영) ≤ 4
--   수능 50%    같은 형태 ≤ 5
--   평가원 50%  (국+수+탐1) ≤ 4  그리고  영어 ≤ 2등급
--   평가원 30%  (국+수+탐1) ≤ 5  그리고  영어 ≤ 2등급
--
--   여기 네 가지가 들어 있는데 기존 컬럼은 threshold 하나 + subject_codes 하나뿐이다.
--     ① 장학 등급마다 기준이 다르다        → scholarship_type
--     ② OR 대안 (탐구 코스 / 영어 코스)     → alternative_group
--     ③ AND 조건 (영어 등급 상한)           → extra_subject_code · extra_max_grade
--     ④ 탐구를 1과목으로 볼지 2과목 평균일지 → elective_mode
--   그래서 기존 시드는 사실상 "평가원 30%" 한 줄이었고 영어 조건이 빠져 있었다.
--
-- ★ 연도별로 바뀌는 것을 값으로 흡수한다. 답변서가 *"해마다 탐구 1과목만 반영하기도
--   하고, 기준도 전년도 난이도에 따라 변경된다"*고 명시했다. 올해 값을 코드에 박으면
--   해마다 "1과목인가 2과목인가"를 다시 물어야 한다. year 는 이미 있다.
--
-- ⚠️ 여전히 전부 active = FALSE 다. 아래 "판정 시점"이 확정되기 전엔 켜지 않는다.

-- ─────────────────────────────────────────────────────────────
-- 1. 컬럼 추가
-- ─────────────────────────────────────────────────────────────

-- 어느 장학에 적용되는 기준인가. NULL = 장학 종류와 무관(벌점 40점이 그렇다).
-- scholarship.scholarship_type 과 같은 값이다 — 거기가 VARCHAR(20) 자유값이라
-- enum 으로 두지 않는다. 지점마다 장학 이름이 다를 수 있다.
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN scholarship_type VARCHAR(20);

-- ★ OR 대안. 같은 (장학, 요건) 안에서 그룹 번호가 다르면 서로 대안이다.
--   판정은 "하나라도 충족하면 통과"이고, 전부 미달일 때만 검토 대상이 된다.
--   반대로 하면(하나라도 미달이면 걸림) 유리한 쪽을 골라주는 규정 취지가 뒤집힌다.
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN alternative_group SMALLINT NOT NULL DEFAULT 1;

-- ★ 어느 회차를 보는가. 수능 기준 장학은 CSAT 를, 평가원 기준은 JUNE·SEPT 를 본다.
--   비어 있으면 JUNE,SEPT (기존 동작 유지).
--   여러 회차면 그중 "좋은 쪽"을 쓴다 — 한 번 못 본 시험 때문에 장학이 날아가면 안 된다.
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN exam_codes VARCHAR(50);

-- ★ 탐구 집계 방식.
--   SINGLE = 탐구 2과목 중 좋은 쪽 1과목만  (평가원 기준 계열)
--   AVG2   = 탐구 2과목 평균                 (수능 기준 계열)
--   NULL   = 탐구를 안 본다                  (국+수+영 대안)
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN elective_mode VARCHAR(10)
        CHECK (elective_mode IS NULL OR elective_mode IN ('SINGLE', 'AVG2'));

-- ★ AND 조건 — 등급합과 별개로 반드시 충족해야 하는 과목 등급 상한.
--   평가원 계열의 *"영어 2등급 이내"*가 이것이다. 등급합만 보면 영어를 아무리 못 봐도
--   국·수·탐으로 메울 수 있어 규정과 달라진다.
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN extra_subject_code VARCHAR(20);
ALTER TABLE scholarship_cancel_rule
    ADD COLUMN extra_max_grade SMALLINT
        CHECK (extra_max_grade IS NULL OR extra_max_grade BETWEEN 1 AND 9);

COMMENT ON COLUMN scholarship_cancel_rule.scholarship_type IS
    '적용 장학. NULL = 장학 종류 무관(벌점). scholarship.scholarship_type 과 같은 값';
COMMENT ON COLUMN scholarship_cancel_rule.alternative_group IS
    '같은 (장학, 요건) 내 OR 대안. 하나라도 충족하면 통과 — 전부 미달일 때만 걸린다';
COMMENT ON COLUMN scholarship_cancel_rule.exam_codes IS
    '대상 회차(콤마). 비면 JUNE,SEPT. 여러 개면 좋은 쪽을 쓴다';
COMMENT ON COLUMN scholarship_cancel_rule.elective_mode IS
    'SINGLE=탐구 좋은 1과목, AVG2=탐구 2과목 평균, NULL=탐구 미포함';
COMMENT ON COLUMN scholarship_cancel_rule.extra_max_grade IS
    'AND 조건. 예: 영어 2등급 이내 — 등급합과 별개로 충족해야 한다';

-- ─────────────────────────────────────────────────────────────
-- 2. 유니크 인덱스 재생성
--
-- 기존은 (year, rule_type) 이라 한 요건에 한 줄만 넣을 수 있었다.
-- 이제 장학 등급 × 대안마다 행이 생기므로 축을 넓힌다.
--
-- ⚠️ scholarship_type 이 NULL 일 수 있어 COALESCE 로 묶는다 — PostgreSQL 은 NULL 을
--    서로 다르게 보기 때문에, 그냥 컬럼을 넣으면 공통 규칙이 몇 개든 들어간다.
-- ─────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS uq_scholarship_rule_common;
DROP INDEX IF EXISTS uq_scholarship_rule_academy;

CREATE UNIQUE INDEX uq_scholarship_rule_common
    ON scholarship_cancel_rule
       (year, rule_type, COALESCE(scholarship_type, ''), alternative_group)
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_scholarship_rule_academy
    ON scholarship_cancel_rule
       (academy_id, year, rule_type, COALESCE(scholarship_type, ''), alternative_group)
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 3. 2026년 기준 재적재
--
-- 기존 EXAM_GRADE_SUM 한 줄(국·수·영 / 5)은 어느 장학의 기준인지 알 수 없는 상태라
-- 지우고 답변서 표대로 다시 넣는다. PENALTY_POINT·MOCK_EXAM_ABSENCE 는 장학 종류와
-- 무관하므로 그대로 둔다.
--
-- ⚠️ 장학 이름(scholarship_type)은 지점이 실제로 쓰는 값과 맞춰야 한다.
--    scholarship 테이블이 자유 문자열이라 여기 값과 다르면 규칙이 아무에게도 안 걸린다.
--    아래는 답변서 표기를 그대로 쓴 것이고, 켜기 전에 실제 값과 대조해야 한다.
-- ─────────────────────────────────────────────────────────────
UPDATE scholarship_cancel_rule
   SET is_deleted = TRUE
 WHERE year = 2026 AND rule_type = 'EXAM_GRADE_SUM' AND academy_id IS NULL;

INSERT INTO scholarship_cancel_rule
    (academy_id, year, rule_type, scholarship_type, alternative_group,
     threshold, subject_codes, exam_codes, elective_mode,
     extra_subject_code, extra_max_grade, active, created_by)
VALUES
    -- 수능 100% — (국+수+탐2평균) ≤ 4  또는  (국+수+영) ≤ 4
    (NULL, 2026, 'EXAM_GRADE_SUM', 'CSAT_100',  1, 4, 'KOREAN,MATH',         'CSAT',      'AVG2',   NULL,      NULL, FALSE, 0),
    (NULL, 2026, 'EXAM_GRADE_SUM', 'CSAT_100',  2, 4, 'KOREAN,MATH,ENGLISH', 'CSAT',      NULL,     NULL,      NULL, FALSE, 0),
    -- 수능 50%
    (NULL, 2026, 'EXAM_GRADE_SUM', 'CSAT_50',   1, 5, 'KOREAN,MATH',         'CSAT',      'AVG2',   NULL,      NULL, FALSE, 0),
    (NULL, 2026, 'EXAM_GRADE_SUM', 'CSAT_50',   2, 5, 'KOREAN,MATH,ENGLISH', 'CSAT',      NULL,     NULL,      NULL, FALSE, 0),
    -- 평가원 50% — (국+수+탐1) ≤ 4 그리고 영어 ≤ 2등급
    (NULL, 2026, 'EXAM_GRADE_SUM', 'KICE_50',   1, 4, 'KOREAN,MATH',         'JUNE,SEPT', 'SINGLE', 'ENGLISH',    2, FALSE, 0),
    -- 평가원 30%
    (NULL, 2026, 'EXAM_GRADE_SUM', 'KICE_30',   1, 5, 'KOREAN,MATH',         'JUNE,SEPT', 'SINGLE', 'ENGLISH',    2, FALSE, 0);
