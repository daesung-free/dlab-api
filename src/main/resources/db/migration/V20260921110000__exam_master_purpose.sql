-- 성적 양식의 용도 구분 — 입학 전 성적 / 디랩에서 본 시험
--
-- ★ 둘이 같은 행을 쓰고 있었다.
--   N수생 입학 성적(작년 6평·9평·수능)을 받는 행이 "2026 · N_SU · JUNE" 인데,
--   올해 디랩에서 치른 6월 평가원을 업로드할 때 고를 수 있는 행도 이것뿐이었다.
--   업로드는 그 회차 점수를 교체하므로, 올해 6평 파일을 올리면 학생이 가입 때 넣고
--   선생님이 성적표로 대조까지 끝낸 작년 6평이 흔적 없이 지워진다.
--
-- ★ 기존 행은 전부 입학 전 성적 양식이다(신상기록부 3종 기준으로 넣은 것).
--   그래서 기본값을 ADMISSION 으로 둔다 — 기존 데이터의 의미가 바뀌지 않는다.
ALTER TABLE exam_master
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'ADMISSION'
        CHECK (purpose IN ('ADMISSION', 'ACADEMY'));

COMMENT ON COLUMN exam_master.purpose IS
    'ADMISSION=입학 전 성적(학생이 가입 때 입력) / ACADEMY=디랩에서 본 시험(연구소 파일 업로드)';

-- 시행일. 디랩 시험은 필수다(서비스가 검사한다).
-- ★ 더프는 매월 치르는 월례고사라 회차 코드만으로는 구분되지 않는다 — 8월 더프와
--   9월 더프가 둘 다 MONTHLY 다. 코드를 달마다 늘리는 대신 시행일로 가른다.
ALTER TABLE exam_master
    ADD COLUMN exam_date DATE;

COMMENT ON COLUMN exam_master.exam_date IS
    '시행일. ACADEMY 양식은 필수 — 같은 달 코드(MONTHLY)끼리 이 값으로 구분한다';

-- 월례고사(더프) 코드 추가
ALTER TABLE exam_master DROP CONSTRAINT IF EXISTS exam_master_exam_code_check;
ALTER TABLE exam_master
    ADD CONSTRAINT exam_master_exam_code_check
        CHECK (exam_code IN ('JUNE', 'SEPT', 'OCT', 'CSAT', 'MONTHLY'));

-- ★ 유니크를 용도·시행일까지 넓힌다.
--   그대로 두면 입학용 "2026 N_SU JUNE" 이 있어서 디랩용 같은 회차를 만들 수 없고,
--   더프는 매월 MONTHLY 라 두 번째 달부터 막힌다.
DROP INDEX IF EXISTS uq_exam_master_common;
DROP INDEX IF EXISTS uq_exam_master_academy;

CREATE UNIQUE INDEX uq_exam_master_common
    ON exam_master (year, grade_type, exam_code, purpose, COALESCE(exam_date, DATE '1900-01-01'))
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_exam_master_academy
    ON exam_master (academy_id, year, grade_type, exam_code, purpose,
                    COALESCE(exam_date, DATE '1900-01-01'))
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;
