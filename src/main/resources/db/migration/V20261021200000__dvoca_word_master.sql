-- D:VOCA 단어 시험 — 원고 적재 (개발문서 v2 7장, 착수 순서 1·2).
--
-- 교재 「D:VOCA」(대성학력개발연구소) 50유닛 × 40단어 = 2,000개.
-- 원고 엑셀 50개 파일을 실측해 구조를 확인했다(v2 3장).
--
-- ★ 왜 지금 적재부터 하는가
--   v2 10장: "1·2는 업체 회신을 기다리지 않고 시작한다. Q3 규칙이 바뀌어도
--   vocab_meaning 을 다시 만들면 되고, 원고 적재 자체는 영향이 없다."
--
-- ★ Part 2·3 테이블도 지금 만든다
--   시험 범위에 넣을지는 미확정(Q8)이지만 데이터는 3종 모두 적재한다.
--   "답을 받기 전에 Part 1 전용으로 구조를 좁히지 말 것."

-- ─────────────────────────────────────────────────────────────
-- Part 1 — 표제어 2,000
--
-- ★ 열 12개를 전부 저장한다. 지금 안 쓰는 것도 둔다 —
--   "2,000행을 다시 넣는 일이 없게"(v2 3.4).
CREATE TABLE vocab_word
(
    id            BIGSERIAL PRIMARY KEY,
    unit_no       SMALLINT     NOT NULL CHECK (unit_no BETWEEN 1 AND 50),
    seq_no        VARCHAR(10)  NOT NULL,
    word          VARCHAR(100) NOT NULL,
    pronunciation VARCHAR(100),
    pos           VARCHAR(20),
    meaning_raw   VARCHAR(500) NOT NULL,
    synonym       VARCHAR(300),
    antonym       VARCHAR(300),
    example_en    VARCHAR(1000),
    example_ko    VARCHAR(1000),
    source        VARCHAR(50),
    derivative    VARCHAR(300),
    etymology     VARCHAR(1000),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT       NOT NULL DEFAULT 0,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- ★ 번호(seq_no)가 전역 유일하다 — 0001~2000 이 빠짐없이 이어진다(실측 확인).
--   같은 유닛을 다시 올리면 번호 기준으로 덮어쓴다.
CREATE UNIQUE INDEX uq_vocab_word_seq ON vocab_word (seq_no) WHERE is_deleted = FALSE;
CREATE INDEX ix_vocab_word_unit ON vocab_word (unit_no) WHERE is_deleted = FALSE;
-- 객관식 보기 추출이 품사로 찾는다(v2 6.1)
CREATE INDEX ix_vocab_word_pos ON vocab_word (pos) WHERE is_deleted = FALSE;

COMMENT ON COLUMN vocab_word.seq_no IS
    '★ 문자열이다 — ''0001'' 처럼 0으로 채워져 있다. 숫자로 읽으면 앞의 0이 날아간다';
COMMENT ON COLUMN vocab_word.pos IS
    '★ 복합 품사가 18.7%다(''v; n''). 단일 값으로 가정하지 말 것 — 보기 추출은 첫 품사를 쓴다';
COMMENT ON COLUMN vocab_word.meaning_raw IS
    '원고 그대로. 정답 후보는 vocab_meaning 이 들고 있다 — 이 칸으로 채점하지 말 것';

-- ─────────────────────────────────────────────────────────────
-- 정답 후보 — 5.2 규칙으로 쪼갠 것
--
-- ★★ 왜 필요한가: 2,000개 중 1,461개(73%)가 한 칸에 여러 뜻을 담고 있다.
--   「financial = 재정의, 금융의」에 학생이 「금융의」라고 쓰면 맞는 답인데
--   원문 완전일치로는 오답이 된다.
CREATE TABLE vocab_meaning
(
    id         BIGSERIAL PRIMARY KEY,
    word_id    BIGINT       NOT NULL REFERENCES vocab_word (id),
    text       VARCHAR(300) NOT NULL,
    origin     VARCHAR(10)  NOT NULL CHECK (origin IN ('SPLIT', 'MANUAL')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT       NOT NULL DEFAULT 0,
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_vocab_meaning ON vocab_meaning (word_id, text) WHERE is_deleted = FALSE;
CREATE INDEX ix_vocab_meaning_word ON vocab_meaning (word_id) WHERE is_deleted = FALSE;

COMMENT ON COLUMN vocab_meaning.origin IS
    '★★ SPLIT 은 원고에서 파생된 것, MANUAL 은 관리자가 넣은 인정 표현이다. '
    '원고를 다시 올리면 SPLIT 만 다시 만들고 MANUAL 은 남긴다 — 지우면 관리자가 '
    '쌓아 둔 인정 표현이 통째로 사라진다';

-- ─────────────────────────────────────────────────────────────
-- Part 3 — 구동사·숙어·혼동어휘 300
--
-- ★ 종류가 유닛 구간마다 다르다(실측):
--     Unit 01~20  구동사    (PHRASAL_VERB)
--     Unit 21~30  혼동 어휘  (CONFUSING)
--     Unit 31~50  숙어      (IDIOM)
--   첫 열 이름으로 판별한다 — 열 개수를 4로 고정하지 말 것(빈 열이 붙은 파일이 있다).
CREATE TABLE vocab_phrase
(
    id         BIGSERIAL PRIMARY KEY,
    unit_no    SMALLINT     NOT NULL CHECK (unit_no BETWEEN 1 AND 50),
    kind       VARCHAR(20)  NOT NULL
        CHECK (kind IN ('PHRASAL_VERB', 'IDIOM', 'CONFUSING')),
    expression VARCHAR(200) NOT NULL,
    meaning    VARCHAR(500),
    example_en VARCHAR(1000),
    example_ko VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT       NOT NULL DEFAULT 0,
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_vocab_phrase ON vocab_phrase (unit_no, expression) WHERE is_deleted = FALSE;

COMMENT ON COLUMN vocab_phrase.example_ko IS
    '⚠️ 줄바꿈이 들어 있다(300건 중 25건 — 뜻이 ①② 두 개인 경우). 그대로 보존한다';

-- ─────────────────────────────────────────────────────────────
-- Part 2 — 예문 빈칸 150
--
-- ⚠️ 시험 범위에 넣을지 미확정이다(Q8). 데이터만 적재해 둔다 —
--   나중에 범위에 들어오면 행을 다시 넣지 않아도 된다.
CREATE TABLE vocab_cloze
(
    id         BIGSERIAL PRIMARY KEY,
    unit_no    SMALLINT     NOT NULL CHECK (unit_no BETWEEN 1 AND 50),
    word       VARCHAR(100),
    pos        VARCHAR(20),
    example_en VARCHAR(1000),
    prompt_ko  VARCHAR(1000),
    answer     VARCHAR(200),
    source     VARCHAR(50),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT       NOT NULL DEFAULT 0,
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_vocab_cloze_unit ON vocab_cloze (unit_no) WHERE is_deleted = FALSE;
