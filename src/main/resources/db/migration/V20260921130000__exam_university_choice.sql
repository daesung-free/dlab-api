-- 지망대학 진단 (앱 시안 4.6 대학 탭)
--
-- ★ 판정은 연구소가 한 것이다. 기준점수·가능성진단·지원자 중 석차를 우리가 계산하지
--   않고 받은 그대로 둔다 — 시안도 "연구소 표기를 그대로 보여준다" 로 정했다.
--   연구소 파일 170~187열(1지망 9열 + 2지망 9열)에 들어 있다.
--
-- ★ 평가원 회차에는 없다(6월 605명 전원 공란). 더프 회차에만 온다 — 행이 없는 것이 정상이다.
--
-- ★ 실적 관리(admission_result)와 다른 데이터다. 저건 "실제로 지원한 대학과 합불"이고
--   이건 "모의고사 볼 때 적은 희망 대학과 그 회차 기준의 가능성"이다. 회차마다 바뀐다.
CREATE TABLE exam_university_choice
(
    id              BIGSERIAL PRIMARY KEY,
    year            SMALLINT      NOT NULL,
    academy_id      BIGINT        NOT NULL REFERENCES academy (id),
    enrollment_id   BIGINT        NOT NULL REFERENCES student_enrollment (id),
    exam_master_id  BIGINT        NOT NULL REFERENCES exam_master (id),

    -- 1지망 / 2지망
    choice_rank     SMALLINT      NOT NULL CHECK (choice_rank IN (1, 2)),

    university_name VARCHAR(100)  NOT NULL,
    department_name VARCHAR(100),

    recruit_quota   INTEGER,       -- 모집정원
    applicant_count INTEGER,       -- 지원자수(그 회차에 같은 학과를 적은 응시자)
    applicant_rank  INTEGER,       -- 지원자 중 석차

    -- 적용된 수능영역("국수영사"). 대학마다 반영 영역이 달라 예상점수의 근거다
    applied_areas   VARCHAR(20),

    -- ★ 소수점이 붙는 대학이 있어 정수로 두지 않는다(환산점수)
    expected_score  NUMERIC(7, 2), -- 본인 수능 예상점수
    cutoff_score    NUMERIC(7, 2), -- 기준점수

    -- 연구소 표기 그대로(위험·불안·소신·가능·안정). enum 으로 묶지 않는다 —
    -- 표기가 바뀌면 파일은 들어오는데 저장이 막힌다
    diagnosis       VARCHAR(10),

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN       NOT NULL DEFAULT FALSE
);

-- 한 회차에 학생당 1지망·2지망 한 줄씩
CREATE UNIQUE INDEX uq_exam_university_choice
    ON exam_university_choice (enrollment_id, exam_master_id, choice_rank)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_exam_university_choice_exam
    ON exam_university_choice (exam_master_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE exam_university_choice IS
    '모의고사 지망대학 진단(연구소 판정 그대로). 실적 관리(admission_result)와 다른 데이터다';
