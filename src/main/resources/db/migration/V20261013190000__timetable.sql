-- 반 주간 시간표 편성 (F-C-3 「시간표·이동수업」).
--
-- 화면은 있는데 **「학기 선택·전 학기 복사·편성 저장」이 전부 「준비 중」**이었다 —
-- 저장할 곳이 없었다. `period_master`(교시)·`curriculum`(커리큘럼)은 있었지만
-- **이동수업(이동반·강의실·담당 선생님)을 담을 데가 없다.**
--
-- ★ 축은 **연도**다. 「기수」라는 별도 축은 없다(2026-10-13 확인 — 우리 용어였고
--   실제로는 연도였다). 그래서 `semester`·`term` 컬럼을 두지 않는다. 두면 화면이
--   학기를 고르는데 값이 하나뿐인 칸이 생기고, 나중에 연도와 어느 쪽이 정본인지 갈린다.

CREATE TABLE timetable_cell
(
    id             BIGSERIAL   PRIMARY KEY,
    year           SMALLINT    NOT NULL,
    academy_id     BIGINT      NOT NULL REFERENCES academy (id),

    -- 편성 단위는 **반**이다. 학생별 이동수업 예외는 1차 범위가 아니다 —
    -- 그건 이 표 위에 덮는 축이라 나중에 별 테이블로 붙는다(아래 COMMENT 참고)
    class_id       BIGINT      NOT NULL REFERENCES class_master (id),

    -- ★ 요일은 MON~SAT 다. **이 학원은 토요일에도 운영한다**(교시 마스터에 토요일 구성이
    --   따로 있다). 일요일은 교시가 없어 칸을 만들 수 없다
    day_of_week    VARCHAR(10) NOT NULL,

    -- ★ 교시 번호를 적지 않고 **교시 마스터를 참조**한다. 번호만 들면 시작·종료 시각을
    --   이 표가 다시 들어야 하고, 교시 시간을 고쳤을 때 **시간표만 옛 시각으로 남는다**.
    --   `period_master` 는 (지점, 연도, 요일구분)별 행이라 연도·지점·요일구분이 함께 따라온다
    period_id      BIGINT      NOT NULL REFERENCES period_master (id),

    -- 과목명. ★ 마스터를 두지 않고 문자열이다 — 과목 마스터가 아직 없고
    -- (`exam_subject` 는 모의고사 전용이라 다르다), 억측으로 미리 만들면 편성 화면이
    -- 실제 과목이 없는 목록에 묶인다
    subject        VARCHAR(100),

    -- 담당 선생님. 비어 있을 수 있다 — 과목만 먼저 깔고 담당을 나중에 붙이는 흐름이 있다
    teacher_id     BIGINT      REFERENCES teacher (id),

    -- ★★ **강의실이 두 군데라는 것이 이 표의 핵심이다.** 반의 홈룸은 `class_master.room_id`,
    --    여기는 **그 칸에서만 쓰는 강의실**(이동수업)이다. 비어 있으면 홈룸으로 떨어지므로
    --    NULL 허용이고, 조회 응답은 「실제 적용 강의실」(셀 ∅ → 홈룸)을 함께 내린다.
    --    한 쪽으로 합치면 둘 중 하나가 사라진다 — 홈룸을 지우면 「이 반이 평소 어디 있나」를
    --    잃고, 셀을 지우면 이동수업을 표현할 수 없다
    room_id        BIGINT      REFERENCES room_master (id),

    -- 전년도 복사 원본. NULL 이면 그 해에 직접 만든 칸이다(`class_master` 와 같은 규약).
    -- 복사본과 신규 편성분을 구분할 유일한 근거다
    copied_from_id BIGINT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ 한 반의 한 칸은 하나다. 없으면 같은 요일·교시에 과목이 둘 생겨
--   화면이 어느 것을 그려야 할지 모른다
CREATE UNIQUE INDEX uq_timetable_slot
    ON timetable_cell (year, class_id, day_of_week, period_id)
    WHERE is_deleted = FALSE;

-- ★★ **(연도, 교시, 요일, 강의실) 이 유일하다 — 프론트가 명시 요청한 제약이다.**
--    없으면 **편성 화면에서 아무리 막아도 뚫린다**: 두 사람이 서로 다른 반을 동시에
--    편성하면 같은 시간 같은 강의실에 두 반이 들어가고, 오류 없이 저장된 뒤 **당일에야**
--    드러난다. 화면 검사는 「지금 보고 있는 반」만 알기 때문이다.
--
--    ⚠️ **강의실이 빈 칸은 제약에서 빠진다**(부분 유니크의 `room_id IS NOT NULL`).
--    셀 강의실은 이동수업일 때만 채우므로 대부분의 칸이 NULL 이고, 그 NULL 들을 제약에
--    넣으면 PostgreSQL 이 NULL 끼리는 다르다고 보아 아무것도 막지 못하는 인덱스가 커진다.
--    홈룸끼리의 겹침은 **홈룸이 반마다 하나로 고정**돼 애초에 생기지 않는다
CREATE UNIQUE INDEX uq_timetable_room
    ON timetable_cell (year, period_id, day_of_week, room_id)
    WHERE room_id IS NOT NULL AND is_deleted = FALSE;

-- 편성 화면은 「그 반의 한 주」를 통째로 읽는다
CREATE INDEX idx_timetable_class ON timetable_cell (academy_id, year, class_id);
-- 되짚는 조회 — 강의실 겹침 안내·선생님별 시간표
CREATE INDEX idx_timetable_room ON timetable_cell (academy_id, year, room_id);
CREATE INDEX idx_timetable_teacher ON timetable_cell (academy_id, year, teacher_id);

COMMENT ON TABLE timetable_cell IS
    '반 주간 시간표 한 칸(요일 × 교시). 학생별 이동수업 예외는 1차 범위가 아니다 — 이 표 위에 덮는 축이다';
COMMENT ON COLUMN timetable_cell.room_id IS
    '그 칸에서만 쓰는 강의실(이동수업). 비면 반 홈룸(class_master.room_id)으로 떨어진다';
COMMENT ON COLUMN timetable_cell.subject IS
    '과목명. 과목 마스터가 아직 없어 문자열이다 — exam_subject 는 모의고사 전용으로 다르다';
