-- 더프 조퇴 — ★ 응시일 마스터 + 신청 종류 (디랩 1029 4-4)
--
-- 디랩 질문: *「사유 조퇴와 ★ 구분하여 신청이 가능할까요?」* → ★ 구분해서 만든다.
--
-- ── ★★ 왜 전용 테이블인가 ────────────────────────────────────
--
-- ★ exam_master 에 ★ exam_date 와 academy(nullable)가 ★ 이미 있다.
--   ⚠️ 그런데 그것은 ★ 성적 입력 양식 마스터다 — gradeType · examName · sortOrder 가
--   함께 있고, ★ 학년마다 행이 갈린다(예비고2·현고3 …).
--
-- ⚠️ 응시일만 필요한데 ★ 양식 행을 만들면
--   ① ★ 학년마다 같은 날짜를 중복해서 넣게 되고
--   ② ★ 성적 입력 화면에 ★ 쓰지 않는 양식이 뜬다
--   ③ ExamCode 에 ★ THE_PREMIUM 을 더해야 하는데, 그건 ★ 성적 축이다
--      (더프 성적은 ★ 엑셀 업로드로 들어온다 — §2 0907)
--
-- ★ 그래서 ★ 「응시일」만 가진 작은 테이블을 둔다.
--
-- ★ academy_id 가 NULL 이면 ★ 전 지점 공통이다 — 1029 지침:
--   *「지점별인지 전국 공통인지는 디랩에 안 물었습니다 — ★ 지점 nullable 로 두면
--     둘 다 받습니다」*

CREATE TABLE duf_exam_date (
    id          BIGSERIAL PRIMARY KEY,
    academy_id  BIGINT      REFERENCES academy (id),
    year        SMALLINT    NOT NULL,
    exam_date   DATE        NOT NULL,
    note        VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ⚠️ 같은 지점·같은 날이 두 번 들어가면 ★ 판정이 두 번 돈다.
--    ★ 전 지점 공통(NULL)과 지점별이 ★ 같은 날일 수는 있다 — 그건 지점이 덮는 것이다.
CREATE UNIQUE INDEX uq_duf_exam_date
    ON duf_exam_date (COALESCE(academy_id, 0), exam_date)
 WHERE is_deleted = FALSE;

CREATE INDEX idx_duf_exam_date_lookup
    ON duf_exam_date (exam_date)
 WHERE is_deleted = FALSE;

COMMENT ON TABLE duf_exam_date IS
    '더프리미엄 모의고사 응시일. 더프 조퇴 신청을 그 날에만 받고, 전일(-1)·당일(-2) '
    '벌점을 가르는 기준이다(1029 4-4). academy_id 가 NULL 이면 전 지점 공통이다.';

COMMENT ON COLUMN duf_exam_date.note IS
    '회차 이름 등. 더프는 월례고사라 매월 있다 — 「7월 더프」처럼 적어 두면 '
    '데스크가 목록에서 고르기 쉽다.';

-- ── 신청 종류 ──────────────────────────────────────────────────
--
-- ★ 더프 조퇴를 ★ 일반 사유 조퇴와 구분한다. ⚠️ 합치면
--   ★ 전일/당일로 점수를 가를 수 없다.

ALTER TABLE absence_reason
    DROP CONSTRAINT IF EXISTS absence_reason_reason_type_check;

ALTER TABLE absence_reason
    ADD CONSTRAINT absence_reason_reason_type_check
        CHECK (reason_type IN ('ABSENCE', 'LATE', 'EARLY_LEAVE', 'OUTING',
                               'DUF_EARLY_LEAVE'));
