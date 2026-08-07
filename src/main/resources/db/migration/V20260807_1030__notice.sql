-- V20260807_1030: 공지 (F-4.11-3)
--
-- ★ 발송 범위가 네 단계다 (화면 정의 menu.ts: ALL / BRANCH / CLASS / INDIVIDUAL).
--   범위마다 쓸 수 있는 사람이 다르다 — 전체는 본사, 지점은 지점관리자, 반은 담임이다.
--   범위를 하나로 합치고 권한만 나누면 "지점관리자가 실수로 전 지점에 공지"가 가능해진다.
--
-- ★ 작성자가 다형이다. 전체공지는 행정(employee)이 쓰고 반공지는 담당선생님(teacher)이
--   쓰는데, 이 둘은 애초에 다른 테이블이다(겸직이 없어 합치지 않기로 했다).
--   그래서 author_type + author_id로 받는다. created_by는 계정 id라 "어느 조직 소속이
--   썼는가"에 답하지 못한다.

CREATE TABLE notice (
    id               BIGSERIAL   PRIMARY KEY,

    -- ★ NULL이면 전 지점(scope=ALL)이다. holiday와 같은 방식.
    academy_id       BIGINT      REFERENCES academy (id),
    year             SMALLINT    NOT NULL,

    scope            VARCHAR(20) NOT NULL
        CHECK (scope IN ('ALL', 'BRANCH', 'CLASS', 'INDIVIDUAL')),
    -- scope=CLASS일 때만 채운다
    class_master_id  BIGINT      REFERENCES class_master (id),
    -- scope=INDIVIDUAL일 때만 채운다
    enrollment_id    BIGINT      REFERENCES student_enrollment (id),

    title            VARCHAR(200) NOT NULL,
    content          TEXT         NOT NULL,

    author_type      VARCHAR(20) NOT NULL CHECK (author_type IN ('EMPLOYEE', 'TEACHER')),
    author_id        BIGINT      NOT NULL,

    -- 상단 고정
    pinned           BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 앱 홈 배너 노출. 시트 F-4.12-3의 데이터 항목이 app_configs와 notices로 나뉘어 있어
    -- 배너는 이쪽이다 — app_config에 넣으면 배너를 고칠 때마다 앱 설정을 건드리게 된다
    banner           BOOLEAN     NOT NULL DEFAULT FALSE,

    -- ★ 예약 발행. NULL이면 즉시 공개다.
    --   "작성 즉시 공개"만 있으면 관리자가 발표 시각에 맞춰 대기해야 한다
    published_at     TIMESTAMPTZ,
    -- 만료. NULL이면 무기한
    expires_at       TIMESTAMPTZ,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,

    -- 범위와 대상이 어긋나면 조회에서 영영 안 걸린다.
    -- 반공지인데 반이 없으면 아무에게도 안 보이고, 전체공지에 반이 붙으면
    -- 어느 쪽 기준으로 보여줄지가 코드마다 갈린다
    CONSTRAINT ck_notice_target CHECK (
        (scope = 'ALL'        AND academy_id IS NULL     AND class_master_id IS NULL AND enrollment_id IS NULL)
     OR (scope = 'BRANCH'     AND academy_id IS NOT NULL AND class_master_id IS NULL AND enrollment_id IS NULL)
     OR (scope = 'CLASS'      AND academy_id IS NOT NULL AND class_master_id IS NOT NULL AND enrollment_id IS NULL)
     OR (scope = 'INDIVIDUAL' AND academy_id IS NOT NULL AND class_master_id IS NULL AND enrollment_id IS NOT NULL)
    )
);

-- 앱 피드가 "내 지점 + 전 지점"을 연도로 훑는다
CREATE INDEX idx_notice_feed
    ON notice (year, academy_id, published_at DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_notice_class ON notice (class_master_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_notice_enrollment ON notice (enrollment_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE notice IS '공지. 범위 4종이고 범위마다 작성 권한이 다르다';
COMMENT ON COLUMN notice.academy_id IS 'NULL이면 전 지점(scope=ALL)';
COMMENT ON COLUMN notice.author_type IS
    '행정(EMPLOYEE)과 담당선생님(TEACHER)은 다른 테이블이라 다형 참조로 받는다';
COMMENT ON COLUMN notice.published_at IS '예약 발행. NULL이면 즉시 공개';
COMMENT ON COLUMN notice.banner IS '앱 홈 배너 노출(F-4.12-3)';
