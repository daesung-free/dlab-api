-- V20260811_1400: Daily Report 집계 (F-4.11-6, 앱 A-3)
--
-- 앱 홈 대시보드의 데이터 원천이다. 대부분은 이미 있는 것을 조합하면 나오므로
-- (출결·순공 → attendance_daily_status, 데일리테스트 → daily_routine_result)
-- 새로 만드는 건 두 개뿐이다: 학생 셀프 피드백, 순공 랭킹.
--
-- ★ 이번 범위에서 뺀 것 — 전부 미확정이라 자리만 비워둔다:
--   · 진도(플래너)     → I-23 보류(입력 주체·% vs ○△✗ 미정). 위젯 자리만 확보하라는 지침
--   · 온라인 질의응답  → F-4.11-7 미구현(멘토 배정 규칙·SLA 미확정). 달력의 '질' 표시는
--                        지금 있는 오프라인 예약 건수로 채운다
--   · 매일 밤 FCM 요약 → E-7(FCM 프로젝트)·문구 미확정

-- ─────────────────────────────────────────────────────────────
-- 학생 셀프 피드백
--
-- ★ 하루 1행이다(enrollment × date). 여러 번 쓰면 마지막 것만 남는다 —
--   "그날의 회고"라 이력을 쌓을 대상이 아니고, 쌓으면 달력이 어느 것을 보여줄지
--   정해야 한다.
--
-- ★ 학생이 직접 쓴 글이라 관리자가 고치지 않는다. 수정·삭제는 본인만 한다 —
--   담임이 손대면 회고가 아니라 제출물이 되어 아무도 솔직하게 안 쓴다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE daily_report_feedback (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    report_date   DATE        NOT NULL,
    content       VARCHAR(500) NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_daily_report_feedback
    ON daily_report_feedback (enrollment_id, report_date) WHERE is_deleted = FALSE;

COMMENT ON TABLE daily_report_feedback IS
    '학생 셀프 피드백. 하루 1행이며 본인만 쓰고 고친다';

-- ─────────────────────────────────────────────────────────────
-- 순공시간 랭킹 (배치 사전집계)
--
-- ★ 여기는 실시간 집계를 하면 안 된다 — 통계 대시보드와 상황이 다르다.
--   대시보드는 관리자 몇 명이 가끔 보지만, 랭킹은 앱 홈이라 전교생이 매일
--   여러 번 연다. 그때마다 전 지점 × 기간을 GROUP BY 하면 같은 답을 수천 번
--   다시 계산한다. 값은 하루에 한 번(출결 확정 후)만 바뀐다.
--
-- ★ scope를 행에 박는다. "전체 1등"과 "지점 1등"을 둘 다 보여주는데(A-3),
--   지점 순위를 전체 순위에서 걸러 뽑으면 지점별 등수를 다시 매겨야 한다.
--
-- ★ 순위를 저장한다 — 조회할 때 매기지 않는다. 동점 처리(같은 순공시간이면
--   같은 등수)를 배치 한 곳에서만 하면 화면마다 갈리지 않는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE study_time_ranking (
    id            BIGSERIAL   PRIMARY KEY,

    -- 전체 랭킹이면 NULL이다. 지점 랭킹이면 그 지점
    academy_id    BIGINT      REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    -- DAILY: 그날 하루 / WEEKLY: 그 주(월~일) / MONTHLY: 그 달
    period_type   VARCHAR(10) NOT NULL
        CHECK (period_type IN ('DAILY','WEEKLY','MONTHLY')),
    -- 기간의 시작일. 주간은 월요일, 월간은 1일
    period_start  DATE        NOT NULL,

    study_minutes INTEGER     NOT NULL,
    -- 1부터. 동점이면 같은 등수를 준다(1,1,3)
    ranking       INTEGER     NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 재실행하면 지우고 다시 넣는다. academy_id가 NULL(전체)이라 COALESCE로 묶는다 —
-- NULL은 UNIQUE에서 서로 다른 값으로 취급돼 그냥 두면 전체 랭킹이 중복 적재된다
CREATE UNIQUE INDEX uq_study_time_ranking
    ON study_time_ranking (COALESCE(academy_id, 0), period_type, period_start, enrollment_id);

-- 상위 N명 조회
CREATE INDEX idx_study_time_ranking_lookup
    ON study_time_ranking (period_type, period_start, COALESCE(academy_id, 0), ranking);

-- 내 등수 조회
CREATE INDEX idx_study_time_ranking_enrollment
    ON study_time_ranking (enrollment_id, period_type, period_start);

COMMENT ON TABLE study_time_ranking IS
    '순공시간 랭킹. 출결 확정 배치 직후 하루 한 번 적재한다 — 앱 홈이 매번 집계하지 않게';
COMMENT ON COLUMN study_time_ranking.academy_id IS
    'NULL이면 전 지점 통합 랭킹';
