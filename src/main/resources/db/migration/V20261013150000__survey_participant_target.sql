-- 설문 대상자 추적 (2026-10-13 디랩 회신 5-1·5-2-1 — 장학 동의서).
--
-- ★★ `survey_participant` 는 지금까지 **「제출 기록」**이었다 — 제출하는 순간 행이 생기고
--    `submitted_at` 이 NOT NULL 이었다. 그래서 **「누가 아직 안 냈나」를 알 수 없었다.**
--
-- 장학 동의서는 「발송 / 열람 / 동의 / 제출」 4단계를 추적해야 하고, 대상이 범위가 아니라
-- **사람 단위**(직원이 장학을 부여한 학생)라 범위 기반으로는 성립하지 않는다.
-- 그래서 이 테이블을 **대상자 테이블**로 바꾼다.
--
-- ★ 기존 설문은 영향이 없다 — 지금 행은 전부 `submitted_at` 이 채워져 있어
--   「발송+열람+동의+제출」로 읽히고, 범위로 돌리는 설문(가채점 등)은 대상자를 미리
--   만들지 않으므로 지금과 똑같이 제출자만 나온다.

ALTER TABLE survey_participant ALTER COLUMN submitted_at DROP NOT NULL;

-- 앱에서 열어 본 시각. ★ 「발송했는데 안 봤다」와 「보고도 안 냈다」는 다른 사건이다 —
-- 전자는 알림을 다시 보내야 하고 후자는 사람이 챙겨야 한다
ALTER TABLE survey_participant ADD COLUMN viewed_at TIMESTAMPTZ;

-- 동의 체크 시각. 제출과 나누는 이유는 **동의만 하고 제출을 안 누른 경우**가 실제로 있어서다
ALTER TABLE survey_participant ADD COLUMN agreed_at TIMESTAMPTZ;

COMMENT ON COLUMN survey_participant.submitted_at IS
    '제출 시각. NULL 이면 미제출 — 대상자로만 등록된 상태다';
COMMENT ON COLUMN survey_participant.viewed_at IS
    '앱에서 열어 본 시각. NULL 이면 열지 않았다';
COMMENT ON COLUMN survey_participant.agreed_at IS
    '동의 체크 시각. 제출과 다른 축이다 — 동의만 하고 제출을 안 누를 수 있다';

-- ★ 설문 종류에 장학 동의서를 더한다. CHECK 제약이라 **값을 추가하지 않으면 생성이 거부된다** —
--   enum 에만 넣고 여기를 빠뜨리면 "코드에는 있는데 저장이 안 되는" 상태가 된다(테스트가 잡았다).
ALTER TABLE survey DROP CONSTRAINT survey_survey_type_check;
ALTER TABLE survey ADD CONSTRAINT survey_survey_type_check
    CHECK (survey_type IN ('GENERAL', 'GRADE_INPUT', 'SCHOLARSHIP_CONSENT'));
