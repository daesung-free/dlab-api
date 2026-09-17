-- 모의고사 파일 식별자 ↔ 학생 연결
--
-- ★ 왜 필요한가
--   업로드는 이름으로 학생을 찾는다. 파일의 식별자(학교코드·반·번호)를 우리 학생과 잇는
--   매핑이 없어서다. 그런데 이름은 동명이인에서 멈춘다 — 둘 중 하나를 고르면 남의 성적이
--   들어가므로 매칭하지 않고 빼는데, 그러면 그 학생은 회차마다 계속 빠진다.
--   한 번 사람이 정해준 연결을 여기 남겨 다음 회차부터 자동으로 쓴다.
--
-- ⚠️ 번호가 "반 번호 + 3자리 순번" 구조라 반이 바뀌면 키가 달라진다.
--   그때 이 행은 안 맞고 이름 매칭으로 떨어진다 — 틀린 학생에게 들어가는 게 아니라
--   다시 물어보는 쪽으로 실패한다. 의도한 동작이다.
CREATE TABLE mock_exam_student_key
(
    id            BIGSERIAL PRIMARY KEY,
    year          SMALLINT    NOT NULL,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),

    -- 파일에서 오는 값 그대로. 전부 문자열이다 — 학교코드가 99700 처럼 0 으로 끝나고
    -- 번호도 자릿수가 의미를 갖는다. 숫자로 바꾸면 앞자리 0 이 사라진다
    school_code   VARCHAR(20) NOT NULL,
    class_no      VARCHAR(20) NOT NULL,
    student_no    VARCHAR(20) NOT NULL,

    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 칸에 연결은 하나다. 둘이면 어느 학생 성적인지 정해지지 않는다
CREATE UNIQUE INDEX uq_mock_exam_student_key
    ON mock_exam_student_key (academy_id, year, school_code, class_no, student_no)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_mock_exam_student_key_enrollment
    ON mock_exam_student_key (enrollment_id);

COMMENT ON TABLE mock_exam_student_key IS '모의고사 엑셀 식별자(학교코드·반·번호) ↔ 재원생 연결. 동명이인 등으로 이름 매칭이 안 될 때 사람이 정한 결과를 남긴다';
