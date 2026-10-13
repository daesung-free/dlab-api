-- 순찰 관리 (2026-10-13 디랩 피드백 1-3 — 신규 기능).
--
-- 지금은 **학생 정보를 종이에 인쇄해 체크한 뒤 DSA 에 다시 입력**한다 — 같은 일을 두 번 한다.
-- 태블릿에서 좌석을 눌러 바로 넣게 한다.

CREATE TABLE patrol_mark
(
    id            BIGSERIAL   PRIMARY KEY,
    year          SMALLINT    NOT NULL,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),

    patrol_date   DATE        NOT NULL,

    -- ★ 회차(1~5)가 저장 키의 일부다. 합치면 **마지막 순찰만 남는다** —
    --   같은 좌석이 1회차에 「졸음」, 3회차에 「화장실」일 수 있다
    round_no      SMALLINT    NOT NULL,

    -- ★★ **좌석이 아니라 학생에 붙인다.** 좌석은 중간에 바뀌는데(재배치) 순찰 기록은
    --    **그때 그 학생**의 상태다. 좌석으로만 남기면 재배치 뒤 기록이 엉뚱한 학생에게 붙는다
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- 찍은 시점의 좌석. ★ 참조가 아니라 **기록**이다 — 「그때 어느 자리였나」를 남긴다.
    -- 좌석이 지워져도 순찰 기록은 남아야 하므로 FK 를 걸지 않는다
    seat_cd       VARCHAR(20),

    mark          VARCHAR(20) NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT patrol_round_range CHECK (round_no BETWEEN 1 AND 5)
);

-- ★ (날짜, 회차, 학생)이 유일하다. 같은 회차를 다시 찍으면 **덮어쓴다** —
--   순찰을 다시 돌 수 있고, 두 행이 남으면 「그 회차에 무엇이었나」에 답이 둘이 된다
CREATE UNIQUE INDEX uq_patrol_mark
    ON patrol_mark (patrol_date, round_no, enrollment_id)
    WHERE is_deleted = FALSE;

-- 그날 그 회차를 통째로 읽는다(화면이 좌석표를 한 번에 그린다)
CREATE INDEX idx_patrol_round ON patrol_mark (academy_id, patrol_date, round_no);

COMMENT ON TABLE patrol_mark IS
    '순찰 기록. 학생 단위로 남긴다 — 좌석은 재배치로 바뀐다';
COMMENT ON COLUMN patrol_mark.seat_cd IS
    '찍은 시점의 좌석번호. 참조가 아니라 기록이다 — 좌석이 지워져도 남는다';
