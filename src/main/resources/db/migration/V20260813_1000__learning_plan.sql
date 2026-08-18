-- V20260813_1000: 주·일 학습계획 — 순번 기반 (F-4.11 / 앱 A-12)
--
-- ★ 교시×요일 그리드는 폐기됐다. 0803 답변서에서 클라이언트가 스스로 철회하고
--   열품타 방식(순번 + 시작시각 + 소요시간 자유 입력)을 요청했다.
--   배경: 디랩은 재수종합반이 아니라 독서실이라 N수생·재학생이 섞여 등원·이용이
--   제각각이고, 정해진 교시 틀에 맞추면 활용성이 낮다.
--
--   그래서 여기에는 period(교시) 참조가 하나도 없다. period_master와 엮지 말 것 —
--   교시는 순공시간 산출(급식·쉬는시간 제외)에서만 쓰는 별개 개념으로 남는다.
--
-- ★ 주도권은 학생이다. 과목별 시간 배분을 학생이 정하고, 담임은 이행 여부·통계만 본다.
--   그래서 관리자 쪽에 수정 API가 없다 — "파란색 = 교사 편집분"이라는 옛 그리드 개념도
--   같이 사라졌다.

-- ─────────────────────────────────────────────────────────────
-- 1. 드롭다운 마스터 (과목 · 학습형태)
--
-- ★ 과목을 코드에 박지 않는다. 요구사항이 "탐구1/탐구2 분리 + 과목 커스터마이즈"라
--   지점·연도마다 다를 수 있고, 실제로 탐구 과목명은 학생 선택에 따라 갈린다.
--
-- ★ 학습형태(수업/인강/자습)도 같은 테이블에 둔다. 한쪽만 enum으로 두면 통계 축이
--   둘인데 조회 경로가 갈려, 나중에 형태가 하나 늘 때 마이그레이션이 필요해진다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE learning_plan_option (
    id          BIGSERIAL   PRIMARY KEY,
    academy_id  BIGINT      NOT NULL REFERENCES academy (id),
    year        SMALLINT    NOT NULL,

    option_type VARCHAR(20) NOT NULL CHECK (option_type IN ('SUBJECT','STUDY_TYPE')),
    label       VARCHAR(30) NOT NULL,
    sort_order  SMALLINT    NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 살아 있는 행만 유일하면 된다. 지운 이름을 다시 못 쓰면 "탐구1"을 지웠다가
-- 다시 만들 수 없다
CREATE UNIQUE INDEX uq_learning_plan_option
    ON learning_plan_option (academy_id, year, option_type, label) WHERE is_deleted = FALSE;

CREATE INDEX idx_learning_plan_option_list
    ON learning_plan_option (academy_id, year, option_type, sort_order) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 2. 하루치 계획
--
-- ★ 축은 "날짜"다. 주간 뷰는 7일을 묶어 보여주는 것일 뿐 저장 단위가 아니다.
--   주 단위로 저장하면 "지난 주 계획 불러오기"가 통째 복사가 되어, 요일 하나만
--   가져오는 것이 안 된다.
--
-- ★ 주말도 대상이다. 이 학원은 토요일에도 운영한다 — 휴일 판정으로 날짜를 막지 말 것.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE learning_plan (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    year          SMALLINT    NOT NULL,
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    plan_date     DATE        NOT NULL,

    -- "지난 주 계획 불러오기"의 원본. NULL이면 직접 만든 날이다.
    -- 어디서 온 건지 남겨두면 복사가 잘못 걸렸을 때 되짚을 수 있다
    copied_from_id BIGINT     REFERENCES learning_plan (id),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 하루에 한 벌. 여러 벌이 쌓이면 어느 것이 그날의 계획인지 통계가 고를 수 없다
CREATE UNIQUE INDEX uq_learning_plan
    ON learning_plan (enrollment_id, plan_date) WHERE is_deleted = FALSE;

-- 주간 조회(날짜 범위) · 통계 집계가 함께 탄다
CREATE INDEX idx_learning_plan_range
    ON learning_plan (enrollment_id, plan_date) WHERE is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 3. 계획 항목
--
-- ★ 순번은 학생이 정하는 값이 아니라 시작시각 순서로 서버가 매긴다. 요구사항이
--   "입력 개수만큼 순번 자동 부여"이고, 학생이 직접 번호를 관리하면 중간에 하나를
--   끼워 넣을 때마다 뒷번호를 전부 다시 눌러야 한다.
--
-- ★ 끝시각이 아니라 소요시간을 저장한다. 입력 단위가 "몇 분 할 것인가"이고,
--   끝시각으로 저장하면 자정을 넘는 항목에서 끝 < 시작이 되어 계산이 깨진다.
--
-- ★ 이행은 O/X 2단계다 (I-19 확정). 부분이행 표현이 없으므로 진행률 컬럼을 만들지 말 것 —
--   만들어두면 화면에서 쓰이지 않는데 통계 쪽에서 섞여 들어간다.
--
-- ★ 진도(교재·인강)는 표시 방식이 보류 상태다(I-23). 지금은 자유 텍스트 한 칸으로만
--   받아두고 구조화하지 않는다 — 확정 전에 필드를 쪼개면 확정된 형태와 어긋난다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE learning_plan_item (
    id          BIGSERIAL   PRIMARY KEY,
    plan_id     BIGINT      NOT NULL REFERENCES learning_plan (id),

    -- 1부터. 시작시각 순으로 서버가 부여한다
    sequence    SMALLINT    NOT NULL CHECK (sequence >= 1),

    start_time  TIME        NOT NULL,
    -- 분 단위. 상한을 두는 이유는 오타(600분)로 통계가 통째로 망가지는 것을 막기 위함
    duration_minutes SMALLINT NOT NULL CHECK (duration_minutes BETWEEN 1 AND 720),

    subject_option_id    BIGINT NOT NULL REFERENCES learning_plan_option (id),
    study_type_option_id BIGINT NOT NULL REFERENCES learning_plan_option (id),

    -- 교재·학습내용. 진도 표시 방식(I-23) 확정 전까지 자유 텍스트
    material    VARCHAR(200),

    -- 이행 O/X
    done        BOOLEAN     NOT NULL DEFAULT FALSE,
    done_at     TIMESTAMPTZ,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ (plan_id, sequence)에 유니크를 걸지 않는다. 하루치를 통째로 다시 쓰는 구조라
--   기존 줄을 soft delete하고 새 줄을 넣는데, Hibernate가 INSERT를 UPDATE보다 먼저
--   내보내므로 "아직 살아 있는 옛 1번"과 "새 1번"이 한순간 공존해 유니크에 걸린다.
--   순번은 서버가 시작시각 순으로 매기는 파생값이라 DB 제약으로 지킬 것도 아니다.
CREATE INDEX idx_learning_plan_item_plan
    ON learning_plan_item (plan_id, sequence) WHERE is_deleted = FALSE;

COMMENT ON COLUMN learning_plan_item.sequence IS
    '시작시각 순으로 서버가 부여. 학생이 직접 지정하지 않는다';
COMMENT ON COLUMN learning_plan_item.duration_minutes IS
    '소요시간(분). 끝시각이 아니라 소요시간이 입력 단위다';
COMMENT ON COLUMN learning_plan_item.done IS
    '이행 O/X 2단계(I-19). 부분이행 표현은 없다';
