-- D:VOCA 응시·답안 (v1 4장 · v2 7장 · 착수 순서 5).
--
-- ★★ 단어·뜻을 고쳐도 지난 응시 기록이 바뀌면 안 된다 (v2 7장)
--   vocab_answer 가 **그때의 답·정오·보기**를 들고 있어야 한다. 단어를 참조만 하면
--   관리자가 뜻을 고치는 순간 "그 학생이 왜 틀렸는지" 를 설명할 수 없다.
--
-- ★ 기록은 영구 보존한다 (v1 9장).

CREATE TABLE vocab_attempt
(
    id            BIGSERIAL PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    round_id      BIGINT      NOT NULL REFERENCES vocab_exam_round (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    started_at    TIMESTAMPTZ NOT NULL,
    submitted_at  TIMESTAMPTZ,
    score         SMALLINT,
    total_count   SMALLINT    NOT NULL,
    retry         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT      NOT NULL DEFAULT 0,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_vocab_attempt_round ON vocab_attempt (round_id, enrollment_id);
CREATE INDEX ix_vocab_attempt_student ON vocab_attempt (enrollment_id, started_at DESC);

COMMENT ON COLUMN vocab_attempt.retry IS
    '재응시. ★ 재응시를 허용하고 전부 저장하며 대표 점수는 최초다 (v1 Q5 현재 처리) — '
    '최고점을 대표로 두면 반복 응시로 점수를 올릴 수 있고, 최근으로 두면 '
    '연습 삼아 다시 푼 것이 기록을 덮는다';
COMMENT ON COLUMN vocab_attempt.submitted_at IS
    'NULL 이면 아직 제출 전이다 — 중간에 앱을 닫은 응시가 남는다. '
    '★ 그 건을 지우지 않는다: 지우면 "몇 번 시도했는가" 가 사라진다';
COMMENT ON COLUMN vocab_attempt.score IS
    '정답 수. NULL 이면 미제출이다 — ★ 0 으로 두면 "다 틀렸다" 와 구분되지 않는다';

CREATE TABLE vocab_answer
(
    id         BIGSERIAL PRIMARY KEY,
    attempt_id BIGINT      NOT NULL REFERENCES vocab_attempt (id),
    word_id    BIGINT      NOT NULL REFERENCES vocab_word (id),
    seq_no     SMALLINT    NOT NULL,
    type       VARCHAR(10) NOT NULL CHECK (type IN ('MC', 'SA')),
    choices    VARCHAR(1000),
    given      VARCHAR(300),
    correct    BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT      NOT NULL DEFAULT 0,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_vocab_answer ON vocab_answer (attempt_id, seq_no);
CREATE INDEX ix_vocab_answer_word ON vocab_answer (word_id);

COMMENT ON COLUMN vocab_answer.choices IS
    '★★ 객관식 보기 스냅샷(구분자 |). 나중에 단어가 바뀌어도 "그때 무엇을 보고 골랐는가" '
    '가 남아야 한다 — 보기를 다시 뽑으면 오답 분석이 성립하지 않는다';
COMMENT ON COLUMN vocab_answer.given IS
    '★ 학생이 낸 답을 문항별로 전부 저장한다 (v1 4.2) — 관리자가 오답을 보고 '
    '인정 표현을 넓히는 근거다. 빈 답(무응답)도 남긴다';
COMMENT ON COLUMN vocab_answer.correct IS
    'NULL 이면 채점 전이다. ★ 채점은 서버에서만 한다 — 정답·정오를 앱으로 내리지 않는다';
