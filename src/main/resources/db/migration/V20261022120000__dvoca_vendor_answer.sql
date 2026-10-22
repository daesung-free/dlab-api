-- D:VOCA 업체 확정 답변 반영 (2026-10-22 「디보카 개발 피드백 답변」).
--
-- ⚠️ 파일명이 14자리인 것은 실수가 아니다. 신규는 밑줄 방식(V<yyyyMMdd>_<HHmm>)이
--   원칙이지만, Flyway 가 밑줄 방식을 20261022.1800 으로 파싱해 **14자리보다 항상 먼저**
--   실행한다. 이 파일은 14자리 마이그레이션이 만든 vocab_answer·vocab_schedule_config 를
--   고치므로 밑줄로 지으면 "relation vocab_answer does not exist" 로 깨진다.
--
-- 개발문서 v2 8장의 미확정 Q2·Q3·Q8·Q9 가 전부 닫혔다. 그중 스키마가 바뀌는 것만 여기 있다.
--
--   Q8  Part 3(구동사·숙어·혼동어휘)을 시험 범위에 넣는다 — 표제어와 같은 방식(뜻 맞히기).
--       Part 2(예문 빈칸)는 넣지 않는다 → vocab_cloze 는 적재만 유지한다.
--   Q2  문항 수 30. ★ 누적 시험(금요일)도 상한 30이다.
--   Q9  결과 화면에 발음·예문·유의어를 보여준다 — 노출을 켜고 끌 수 있게 한다.
--   Q3  주관식 채점 규칙은 우리 제안이 그대로 승인됐다 — 코드(MeaningSplitter)뿐이라
--       스키마 변경이 없다.

-- ─────────────────────────────────────────────────────────────
-- Part 3 을 출제한다 — 문항이 표제어가 아닐 수 있다
--
-- ★★ 왜 word_id 를 NULL 허용으로 바꾸는가
--   Part 3 는 vocab_phrase 에 있다. vocab_word 에 구동사를 끼워 넣으면 2,000개 번호
--   체계(seq_no 0001~2000)가 깨지고, 재업로드가 번호로 덮어쓰는 구조도 무너진다.
--
-- ★ 둘 중 정확히 하나만 채운다
--   둘 다 비면 "무엇을 물었는지" 가 사라지고, 둘 다 차면 채점이 어느 쪽 뜻을 보는지가
--   코드에 숨는다. 제약으로 못 박는다.
ALTER TABLE vocab_answer
    ALTER COLUMN word_id DROP NOT NULL;

ALTER TABLE vocab_answer
    ADD COLUMN phrase_id BIGINT REFERENCES vocab_phrase (id);

ALTER TABLE vocab_answer
    ADD CONSTRAINT ck_vocab_answer_item
        CHECK ((word_id IS NOT NULL AND phrase_id IS NULL)
            OR (word_id IS NULL AND phrase_id IS NOT NULL));

CREATE INDEX ix_vocab_answer_phrase ON vocab_answer (phrase_id);

COMMENT ON COLUMN vocab_answer.phrase_id IS
    '★ Part 3(구동사·숙어·혼동어휘) 문항이면 이쪽이 채워진다 (업체 확정 2026-10-22). '
    'word_id 와 정확히 하나만 채운다 — 오답 확인 화면이 둘을 다른 목록으로 본다';

-- ─────────────────────────────────────────────────────────────
-- 출제 설정 — 문항 수 30 · Part 3 포함 · 결과 상세 노출
--
-- ⚠️ 기본값만 바꾸면 이미 만들어진 지점 설정 행은 그대로 20·40 으로 남는다.
--   그래서 **아직 손대지 않은 행(종전 기본값 그대로인 행)만** 30 으로 올린다 —
--   관리자가 일부러 바꿔 둔 값을 덮으면 그쪽이 더 나쁜 사고다.
ALTER TABLE vocab_schedule_config
    ALTER COLUMN question_count SET DEFAULT 30,
    ALTER COLUMN weekly_question_cap SET DEFAULT 30;

UPDATE vocab_schedule_config
SET question_count      = 30,
    weekly_question_cap = 30
WHERE question_count = 20
  AND weekly_question_cap = 40;

ALTER TABLE vocab_schedule_config
    ADD COLUMN include_phrase    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN show_word_detail  BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN vocab_schedule_config.question_count IS
    '평일 회차 문항 수. ★ 업체 확정 30 (2026-10-22). 범위의 문항 수보다 크면 그 수로 줄어든다';
COMMENT ON COLUMN vocab_schedule_config.weekly_question_cap IS
    '금요일 누적 상한. ★ 업체 확정 30 — 3회독 누적은 최대 20유닛 800단어라 '
    '상한 없이는 800문항이 된다';
COMMENT ON COLUMN vocab_schedule_config.include_phrase IS
    '★ Part 3(구동사·숙어·혼동어휘)을 시험 범위에 넣는가. 업체 확정으로 기본 TRUE — '
    '유닛당 6문항이 늘어난다(표제어 40 + Part 3 6). ⚠️ Part 2(예문 빈칸)는 범위 밖이다';
COMMENT ON COLUMN vocab_schedule_config.show_word_detail IS
    '★ 결과 화면에 발음·예문·유의어·반의어·파생어를 보여주는가(업체 확정, 기본 TRUE). '
    '데이터는 늘 저장하고 노출만 끈다 — 끄려고 2,000행을 다시 넣는 일이 없게';
