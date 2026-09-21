-- 가채점 설문 확장 (0921 성적 문서 11장)
--
-- 연구소가 쓰던 가채점 화면은 9페이지다(신원확인 → 한국사 → 국어 → 수학 → 영어 → 탐구1 → 탐구2
-- → 제2외국어 → 제출). 기존 설문으로 받으려면 네 가지가 없었다.
--   ① 응시/미응시 분기 — 미응시면 그 과목 문항을 건너뛴다
--   ② 자동 합산 — 공통 + 선택 = 총점
--   ③ 임시저장 — 9페이지 중간에 나가면 처음부터였다
--   ④ 재제출 — 가채점은 오타 정정이 잦다
-- (선택과목별 만점은 문항마다 범위를 따로 걸어 이미 된다 — 국어 선택 24 / 수학 선택 26)

-- ④ 제출 후 수정 허용. 익명 설문은 안 된다 — 응답자를 저장하지 않아 고칠 응답을 찾을 수 없다
ALTER TABLE survey
    ADD COLUMN allow_edit BOOLEAN NOT NULL DEFAULT FALSE;

-- ① 이 문항은 (그 문항에서 그 선택지를 골랐을 때만) 보인다. 비어 있으면 항상 보인다
ALTER TABLE survey_question
    ADD COLUMN show_if_question_id BIGINT REFERENCES survey_question (id),
    ADD COLUMN show_if_option_id   BIGINT REFERENCES survey_question_option (id),
    -- ② 합산 문항 — 같은 설문 문항 번호(seq) 목록("3,4"). 값은 서버가 채우고 앱이 보낸 값은 버린다
    ADD COLUMN sum_of_seqs         VARCHAR(100),
    ADD CONSTRAINT ck_survey_question_show_if CHECK (
        (show_if_question_id IS NULL) = (show_if_option_id IS NULL)
    );

-- ③ 임시저장. 제출 전 답을 검증 없이 그대로 둔다 — 제출할 때 검증한다
CREATE TABLE survey_draft
(
    id            BIGSERIAL PRIMARY KEY,
    survey_id     BIGINT      NOT NULL REFERENCES survey (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    answers_json  JSONB       NOT NULL,
    saved_at      TIMESTAMPTZ NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_survey_draft
    ON survey_draft (survey_id, enrollment_id)
    WHERE is_deleted = FALSE;

COMMENT ON TABLE survey_draft IS
    '설문 임시저장. 익명 설문은 받지 않는다 — 응답자와 답이 한 행에 묶인다';
