-- 학생 메모 (2026-10-13 디랩 피드백 5-2 「비고/메모」).
--
-- ★★ **칸 하나로 두지 않는다.** 단일 문자열이면 **서로 덮어쓰고 누가 언제 썼는지가
--    사라진다.** 운영에서는 상담 메모처럼 쓰이고 담당자가 여럿이다.
--    입학예약 메모(`admission_reservation_memo`)와 **같은 모양**으로 쌓는다.

CREATE TABLE student_memo
(
    id            BIGSERIAL PRIMARY KEY,
    year          SMALLINT     NOT NULL,
    academy_id    BIGINT       NOT NULL REFERENCES academy (id),

    -- ★ 사람(`student`)이 아니라 **등록 건**에 붙인다. 학생이 다음 기수에 재등록하면
    --   그해 메모가 새 기수에 섞이면 안 된다(§3 2단 구조와 같은 판단)
    enrollment_id BIGINT       NOT NULL REFERENCES student_enrollment (id),

    content       VARCHAR(2000) NOT NULL,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 작성자. `SecurityAuditorAware` 가 채운다 — 직접 넣지 않는다
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 최근 것이 위로. 목록이 늘 이 순서로 나간다
CREATE INDEX idx_student_memo_enrollment ON student_memo (enrollment_id, id DESC);

COMMENT ON TABLE student_memo IS
    '학생 메모. 덮어쓰지 않고 쌓는다 — 누가 언제 썼는지가 남아야 한다';
