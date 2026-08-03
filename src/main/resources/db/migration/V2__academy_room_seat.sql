-- V2: 지점 스키마 확정(ALTER) · 지점 설정 · 강의실 · 구역 · 좌석 · 좌석배정
--
-- V1이 "V2에서 다른 담당자가 만들 것"으로 남겨둔 영역이다(V1 21행, 380~388행).
-- 레거시 좌석/구역 스키마가 미확보라 추측으로 만들지 않고, DSA 호환 키오스크 계약
-- (getDlabList · getStudyAreaInfo · getStudyAreaSeatInfo · getStudyAreaSeatState · setSeatChgProc)
-- 이 실제로 주고받는 필드에서 역산해 설계했다. 계약 상세는 docs/dsa-compat.md.
--
-- 공통 컬럼 규칙(V1 §0-1)을 그대로 따른다: id · academy_id · created_at · updated_at
--   · created_by · is_deleted.  updated_at은 트리거가 아니라 JPA Auditing이 채운다(V1과 동일).
--
-- ★ 좌석이탈 기록(seat_leave_record)은 여기서도 만들지 않는다 —
--   스코프 자체가 미확정이고(I-16), 0803에 "앱 신청 경로 없음(조회 전용)"으로 확정됐다.


-- ==========================================================================
-- 1. 지점 확정  (getDlabList 계약에 맞춤)
-- ==========================================================================

-- getDlabList는 acad_cd / acad_nm / full_nm 3개를 반환한다.
-- V1의 academy에는 full_nm이 없어 계약을 충족하지 못한다.
--   acad_nm  = 짧은 표시명 (예: 분당)
--   full_nm  = 정식 명칭   (예: DLab 분당)
ALTER TABLE academy ADD COLUMN full_nm VARCHAR(100);

COMMENT ON COLUMN academy.acad_cd IS
    'DSA 지점코드. 대성전산이 부여한 값이라 연속이 아니다(31~34 다음 42) — 임의 재부여 금지';
COMMENT ON COLUMN academy.full_nm IS
    'DSA getDlabList의 full_nm. 미입력 시 acad_nm으로 대체해 응답한다';

-- 키오스크 백엔드 stores.store_code 대응. 전환 시 지점 대조에 쓴다.
ALTER TABLE academy ADD COLUMN store_code VARCHAR(20);

CREATE UNIQUE INDEX uq_academy_store_code
    ON academy (store_code) WHERE store_code IS NOT NULL;


-- ==========================================================================
-- 2. 지점 설정  (요구사항 F-4.10-7 branch_configs)
-- ==========================================================================

-- 지점마다 다른 외부 연동 설정값을 한 곳에 모은다.
-- 키오스크 시크릿 · PG 가맹점코드(MID) · Nebula 장비 ID · 지점별 정책 JSON.
-- SUPER_ADMIN 전용 화면에서 관리하며, 시크릿은 마스킹 표시 + 재발급 시 감사로그를 남긴다.
--
-- ★ 지점당 1행이다. 물리 키오스크가 여러 대여도 상관없다 —
--   기기는 우리에게 직접 인증하지 않고 키오스크 백엔드가 지점 단위로 한 번 인증한다
--   (키오스크 백엔드의 stores 테이블도 지점당 credential 1벌 구조다).
--
-- 키오스크 인증 검증식: secret_id == MD5(yyyyMMdd + kiosk_secret)
--   ★ 날짜가 섞이므로 자정 경계 처리가 필요하다(요청 시각 기준 당일/전일 모두 허용 검토).
--   ★ MD5 원문을 다시 계산해야 해서 kiosk_secret을 해시로 저장할 수 없다.
--     사실상 평문 비밀값이므로 저장소 암호화(RDS at-rest)를 반드시 켜고,
--     실제 값은 마이그레이션·시드에 넣지 말 것(CLAUDE.md §8).
CREATE TABLE branch_config (
    id                BIGSERIAL    PRIMARY KEY,
    academy_id        BIGINT       NOT NULL REFERENCES academy (id),
    -- 키오스크 DSA 호환 인증
    kiosk_client_id   VARCHAR(100),
    kiosk_secret      VARCHAR(200),
    -- 디랩 자체 명의 PG 가맹점코드. 급식·등록비 공통(MID 분리 여부는 미확정)
    pg_merchant_code  VARCHAR(100),
    -- Zyxel Nebula 제어 대상. 단말 단위인지 정책 단위인지 미확정(E-1)이라 문자열로 둔다
    nebula_device_id  VARCHAR(100),
    -- 지점별 정책값(등원 마감시각 예외 등). 스키마 확정 전 임시 수용소로 남용하지 말 것
    config_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        BIGINT,
    is_deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_branch_config_academy UNIQUE (academy_id)
);

COMMENT ON TABLE branch_config IS
    '지점별 외부 연동 설정(F-4.10-7). 지점당 1행. SUPER_ADMIN 전용, 시크릿 마스킹 필수';
COMMENT ON COLUMN branch_config.kiosk_secret IS
    'MD5(yyyyMMdd+secret) 재계산이 필요해 해시 불가. 저장소 암호화 필수, 값을 커밋하지 말 것';

-- client_id로 지점을 역조회한다(/auth/token 검증 경로)
CREATE UNIQUE INDEX uq_branch_config_kiosk_client_id
    ON branch_config (kiosk_client_id) WHERE kiosk_client_id IS NOT NULL;


-- ==========================================================================
-- 3. 강의실
-- ==========================================================================

-- 수업이 이루어지는 공간. 자습 구역(study_area)과는 별개다.
-- 기초관리(요구사항정의서 F-4.10-1)의 "강의실" 항목에 대응하며 키오스크 계약과는 무관하다.
CREATE TABLE room_master (
    id         BIGSERIAL   PRIMARY KEY,
    academy_id BIGINT      NOT NULL REFERENCES academy (id),
    room_no    VARCHAR(20) NOT NULL,
    name       VARCHAR(50),
    capacity   SMALLINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_room_master UNIQUE (academy_id, room_no)
);

COMMENT ON TABLE room_master IS '강의실(수업 공간). 자습 구역은 study_area 참고';


-- ==========================================================================
-- 4. 자습 구역
-- ==========================================================================

-- 근거: getStudyAreaInfo → area_cd / area_nm
-- 키오스크 백엔드는 이를 "독서실 구역(강의실)"으로 부른다. 좌석이 속하는 단위다.
CREATE TABLE study_area (
    id         BIGSERIAL    PRIMARY KEY,
    academy_id BIGINT       NOT NULL REFERENCES academy (id),
    area_cd    VARCHAR(50)  NOT NULL,
    area_nm    VARCHAR(100) NOT NULL,
    sort_order SMALLINT     NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT,
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_study_area UNIQUE (academy_id, area_cd)
);

COMMENT ON TABLE study_area IS '자습 구역(독서실). DSA area_cd 체계를 그대로 쓴다';


-- ==========================================================================
-- 5. 좌석
-- ==========================================================================

-- 근거: getStudyAreaSeatInfo → seat_cd / seat_nm / xpos(x_pos) / ypos(y_pos) / seat_gn
--
-- ★ 좌표는 키오스크가 좌석배치도를 그리는 데 쓰므로 반드시 내려줘야 한다.
--   응답 키가 xpos/x_pos, ypos/y_pos로 혼재하는데 클라이언트가 양쪽을 모두 읽으므로
--   서버는 기존 표기를 유지한다(docs/dsa-compat.md §2 — 정리하지 말 것).
-- ★ seat_gn은 좌석 자체의 사용가능 여부다. 값이 없으면 클라이언트가 'Y'로 간주한다.
--   착석/외출 같은 실시간 상태(state)는 여기 두지 않는다 — 아래 seat_assignment에서 파생한다.
CREATE TABLE seat_master (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    study_area_id BIGINT      NOT NULL REFERENCES study_area (id),
    seat_cd       VARCHAR(50) NOT NULL,
    seat_nm       VARCHAR(50),
    x_pos         INT         NOT NULL DEFAULT 0,
    y_pos         INT         NOT NULL DEFAULT 0,
    -- DSA seat_gn. 응답 시 Y/N으로 변환한다.
    usable        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_seat_master UNIQUE (academy_id, seat_cd)
);

COMMENT ON TABLE seat_master IS '좌석. DSA seat_cd 체계. 좌표는 키오스크 좌석배치도용';
COMMENT ON COLUMN seat_master.usable IS 'DSA seat_gn — 좌석 자체의 사용가능 여부(실시간 착석 여부 아님)';

CREATE INDEX idx_seat_master_area ON seat_master (study_area_id) WHERE NOT is_deleted;


-- ==========================================================================
-- 6. 좌석 배정
-- ==========================================================================

-- 근거: getStdInfoList가 학생별 seat_cd를 반환하고, setSeatChgProc(rfid_no, seat_cd)로 변경된다.
--
-- ★ 배정 대상은 학생(사람)이 아니라 등록 건이다 — 사물함(locker_master)과 같은 원칙.
--   1년 단위 코호트라 좌석도 매년 새로 배정되고, 사람에 붙이면 작년 배정이 남는다.
-- ★ 이력을 남긴다. 좌석 이동(setSeatChgProc)이 수시로 일어나고, 나중에 "그 시각에 누가
--   어느 좌석이었나"를 출결·자리이탈과 대조해야 하므로 덮어쓰면 안 된다.
CREATE TABLE seat_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    seat_id       BIGINT      NOT NULL REFERENCES seat_master (id),
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE seat_assignment IS '좌석 배정 이력. 현재 배정은 released_at IS NULL 인 행';

-- 한 좌석에 현재 배정은 하나뿐
CREATE UNIQUE INDEX uq_seat_assignment_active_seat
    ON seat_assignment (seat_id) WHERE released_at IS NULL AND NOT is_deleted;

-- 한 등록 건에 현재 좌석은 하나뿐
CREATE UNIQUE INDEX uq_seat_assignment_active_enrollment
    ON seat_assignment (enrollment_id) WHERE released_at IS NULL AND NOT is_deleted;

CREATE INDEX idx_seat_assignment_enrollment ON seat_assignment (enrollment_id);
