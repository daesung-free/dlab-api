-- D:VOCA 출제 설정 (v1 Q2 · v2 6.2 — 업체 확인 중).
--
-- ★★ 코드에 박지 않는다 (v2 6.2: "문항 수·객관식 비율·보기 수는 전부 설정값으로 뺀다")
--   업체 회신이 오면 행만 고친다. 기본값은 v2 6.2 의 제안("20문항 안팎")을 따른다.
--
-- ★ 누적 시험에 상한이 필요하다
--   3회독 금요일 누적은 범위가 최대 20유닛 = 800단어다. 800문항을 낼 수는 없다 —
--   범위에서 무작위로 뽑고 상한을 둔다.
--
-- ⚠️ 지점별이다. 지점마다 시험 운영이 다를 수 있고, 한 지점에서 바꾼 값이
--   다른 지점 시험 문항 수를 바꾸면 안 된다.

ALTER TABLE vocab_schedule_config
    ADD COLUMN question_count       SMALLINT NOT NULL DEFAULT 20,
    ADD COLUMN weekly_question_cap  SMALLINT NOT NULL DEFAULT 40,
    ADD COLUMN choice_count         SMALLINT NOT NULL DEFAULT 4,
    ADD COLUMN multiple_choice_rate SMALLINT NOT NULL DEFAULT 50;

ALTER TABLE vocab_schedule_config
    ADD CONSTRAINT ck_vocab_question_count CHECK (question_count BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_vocab_weekly_cap CHECK (weekly_question_cap BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_vocab_choice_count CHECK (choice_count BETWEEN 2 AND 6),
    ADD CONSTRAINT ck_vocab_mc_rate CHECK (multiple_choice_rate BETWEEN 0 AND 100);

COMMENT ON COLUMN vocab_schedule_config.question_count IS
    '평일 회차 문항 수. 기본 20 (v2 6.2 제안). ★ 범위의 단어 수보다 크면 단어 수로 줄어든다';
COMMENT ON COLUMN vocab_schedule_config.weekly_question_cap IS
    '금요일 누적 상한. ★ 3회독 누적은 최대 20유닛 = 800단어라 상한 없이는 800문항이 된다';
COMMENT ON COLUMN vocab_schedule_config.choice_count IS
    '객관식 보기 수(정답 포함). ⚠️ 2 미만이면 고를 것이 없다';
COMMENT ON COLUMN vocab_schedule_config.multiple_choice_rate IS
    '객관식 비율(%). 0=전부 주관식 · 100=전부 객관식. '
    '★ 주관식이 섞여야 뜻을 쓰게 되고, 그 답이 인정 표현을 넓히는 근거가 된다';
