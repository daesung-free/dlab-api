-- V20260821_1000: 교습비 가격 마스터 + 월별 교습일수 (F-4.8 · F-4.10-5)
--
-- 0820 교습비·환불 규정으로 확정된 것을 담는다.
--
-- ★ 결제는 "교습비 + 독서실비" 2축이다. 한 값으로 합쳐 두면 안 된다:
--     · 할인은 교습비에만 붙는다 (독서실비는 할인 없음)
--     · 환불 산식이 다르다 (교습비=구간, 독서실비=일할)
--   그래서 가격도 두 컬럼으로 나눠 저장한다. 750,000 하나로 두면 나중에 못 가른다.
--
-- ★ "1일 교습비 자동계산"이 이 마이그레이션의 목적이다.
--   지금은 사람이 660,000÷27 같은 나눗셈을 손으로 해서 넣고 있다 —
--   할인 6단계 × 상품 5종 × 12개월이라 채우는 칸이 수백 개다.
--   교습비·독서실비와 그 달의 교습일수만 있으면 나머지는 전부 파생된다.

-- ─────────────────────────────────────────────────────────────
-- 1. 가격 마스터
--
-- ★ academy_id가 NULL이면 전 지점 공통이다(holiday·terms·exam_master와 같은 규약).
--   조회는 "그 지점 행이 있으면 그것만, 없으면 공통본"으로 가른다.
--
--   이 규약 덕분에 행이 확 줄어든다. 전수로 넣으면 11지점 × 3학년 × 2좌석 = 66행인데,
--   실제로는 공통 3행 + 예외 5행이면 끝난다:
--     공통   N수/일반 660,000+90,000 · 재학생(고2·고3)/일반 400,000+90,000
--     목동·분당  재학생도 660,000+90,000  (이 두 지점만 N수와 같다)
--     1인실     분당 660,000+190,000 · 목동 820,000+130,000 · 대구 822,000+128,000
--
-- ★ 연도가 키에 들어간다. 내년에 교습비·독서실비 인상 계획이 있다고 명시됐고,
--   과거 청구가 소급해서 바뀌면 안 된다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE tuition_price (
    id              BIGSERIAL   PRIMARY KEY,
    academy_id      BIGINT      REFERENCES academy (id),
    year            SMALLINT    NOT NULL,

    grade_type      VARCHAR(10) NOT NULL CHECK (grade_type IN ('HIGH2','HIGH3','N_SU')),
    -- GENERAL = 일반좌석, SINGLE = 1인실
    seat_type       VARCHAR(10) NOT NULL CHECK (seat_type IN ('GENERAL','SINGLE')),

    -- 할인 대상. 환불은 구간(1/3까지 2/3, 1/2까지 1/2, 이후 없음)
    tuition_fee     INTEGER     NOT NULL CHECK (tuition_fee >= 0),
    -- 할인 없음. 환불은 일할
    study_room_fee  INTEGER     NOT NULL CHECK (study_room_fee >= 0),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ★ PostgreSQL은 NULL을 서로 다른 값으로 보므로 academy_id를 낀 유니크 하나로는
--   공통 행의 중복을 못 막는다. 부분 인덱스 두 벌로 나눈다(terms·exam_master와 같은 처리)
CREATE UNIQUE INDEX uq_tuition_price_common
    ON tuition_price (year, grade_type, seat_type)
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_tuition_price_academy
    ON tuition_price (academy_id, year, grade_type, seat_type)
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

-- ─────────────────────────────────────────────────────────────
-- 2. 월별 교습일수
--
-- ★ 달력 일수가 아니다. 클라이언트가 준 표가 2월 27일 · 9월 29일로 잡혀 있다
--   (달력은 28·30). 설·추석 당일을 뺀 것으로 보이나 확정되지 않았고,
--   삼일절·어린이날·광복절 등은 빼지 않았다(3·5·8·10·12월이 전부 31일).
--
-- ★ 그래서 holiday 테이블로 계산하지 않는다. 그건 급식 가능일용이라 법정공휴일이
--   전부 들어가 3월이 30일이 된다 — "급식 쉬는 날"과 "교습비에서 빼는 날"은
--   다른 개념이다. 규칙을 추측해 자동 산출하면 조용히 틀린 금액이 나온다.
--
--   대신 학원이 아는 값을 그대로 받는다. 연 1회 12행이면 되고,
--   그것만으로 손계산 수백 칸이 사라진다.
--
-- ★ 이 값이 세 군데에 쓰인다 — 중도 입학 결제(수업일수 × 1일 교습비),
--   퇴원 시 독서실비 일할 환불, 장학 취소 시 정상가 재결제.
--   틀리면 셋 다 틀리고, 하루 몇백 원씩 어긋난 게 정산에서 쌓인다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE tuition_month (
    id             BIGSERIAL   PRIMARY KEY,
    academy_id     BIGINT      REFERENCES academy (id),
    year           SMALLINT    NOT NULL,
    month          SMALLINT    NOT NULL CHECK (month BETWEEN 1 AND 12),

    -- 그 달에 실제로 교습하는 일수. 상한은 31 — 달력을 넘는 값은 오타다
    teaching_days  SMALLINT    NOT NULL CHECK (teaching_days BETWEEN 1 AND 31),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_tuition_month_common
    ON tuition_month (year, month)
    WHERE academy_id IS NULL AND is_deleted = FALSE;

CREATE UNIQUE INDEX uq_tuition_month_academy
    ON tuition_month (academy_id, year, month)
    WHERE academy_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON COLUMN tuition_month.teaching_days IS
    '그 달의 교습일수. 달력 일수가 아니다 — 2026년 표 기준 2월 27일 · 9월 29일';
COMMENT ON COLUMN tuition_price.study_room_fee IS
    '독서실비. 할인이 없고 환불이 일할이라 교습비와 반드시 분리해 둔다';

-- ─────────────────────────────────────────────────────────────
-- 3. 2026년 초기 데이터
--
-- 0820 파일 그대로다. ⚠️ 연도가 바뀌면 관리자 화면에서 새로 넣어야 한다 —
-- 안 넣으면 그 해 청구에서 1일 교습비를 계산할 수 없다.
--
-- 1인실은 지점별로만 존재한다(분당·목동·대구). 공통 행을 두지 않는 이유는,
-- 두면 1인실이 없는 지점에서도 상품이 있는 것처럼 보이기 때문이다.
--
-- ⚠️ 지점별 행은 academy 를 JOIN 해서 넣는다 — 이 마이그레이션이 도는 시점에
--    없는 지점은 행이 생기지 않는다. 대전·대구는 아직 등록 전이고, 지점을 나중에
--    추가하면 그 지점 가격은 관리자 화면에서 따로 넣어야 한다.
--    (넣지 않아도 공통 행으로 떨어지므로 조용히 틀린 금액이 나오지는 않고,
--     1인실만 "상품 없음"이 된다)
-- ─────────────────────────────────────────────────────────────

-- 공통 — N수는 전 지점 동일, 재학생은 기본 400,000
INSERT INTO tuition_price (academy_id, year, grade_type, seat_type,
                           tuition_fee, study_room_fee, created_by)
VALUES
    (NULL, 2026, 'N_SU',  'GENERAL', 660000, 90000, 0),
    (NULL, 2026, 'HIGH3', 'GENERAL', 400000, 90000, 0),
    (NULL, 2026, 'HIGH2', 'GENERAL', 400000, 90000, 0);

-- 목동·분당은 재학생도 N수와 같다
INSERT INTO tuition_price (academy_id, year, grade_type, seat_type,
                           tuition_fee, study_room_fee, created_by)
SELECT a.id, 2026, g.grade, 'GENERAL', 660000, 90000, 0
FROM academy a
CROSS JOIN (VALUES ('HIGH3'), ('HIGH2')) AS g(grade)
WHERE a.acad_nm IN ('분당', '목동');

-- 1인실 — 지점마다 금액이 다르다
INSERT INTO tuition_price (academy_id, year, grade_type, seat_type,
                           tuition_fee, study_room_fee, created_by)
SELECT a.id, 2026, g.grade, 'SINGLE', p.tuition, p.room, 0
FROM academy a
JOIN (VALUES
        ('분당', 660000, 190000),
        ('목동', 820000, 130000),
        ('대구', 822000, 128000)
    ) AS p(name, tuition, room) ON p.name = a.acad_nm
CROSS JOIN (VALUES ('N_SU'), ('HIGH3'), ('HIGH2')) AS g(grade);

-- 월별 교습일수 — 전 지점 공통
INSERT INTO tuition_month (academy_id, year, month, teaching_days, created_by)
VALUES
    (NULL, 2026,  1, 31, 0),
    (NULL, 2026,  2, 27, 0),   -- ★ 달력은 28일
    (NULL, 2026,  3, 31, 0),
    (NULL, 2026,  4, 30, 0),
    (NULL, 2026,  5, 31, 0),
    (NULL, 2026,  6, 30, 0),
    (NULL, 2026,  7, 31, 0),
    (NULL, 2026,  8, 31, 0),
    (NULL, 2026,  9, 29, 0),   -- ★ 달력은 30일
    (NULL, 2026, 10, 31, 0),
    (NULL, 2026, 11, 31, 0),
    (NULL, 2026, 12, 31, 0);
