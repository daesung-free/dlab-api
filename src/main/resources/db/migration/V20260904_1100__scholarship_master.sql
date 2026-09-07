-- V20260904_1100: 장학 종류 마스터
--
-- ★ 왜 필요한가 — 지금 장학 종류가 어디에도 정의돼 있지 않다.
--   `scholarship.scholarship_type` 이 VARCHAR(20) 자유 문자열이고 데스크가 직접 친다.
--   그런데 `scholarship_cancel_rule.scholarship_type` 이 그 문자열로 규칙을 매칭한다
--   (V20260830_1000 §"장학 이름은 지점이 실제로 쓰는 값과 맞춰야 한다").
--
--   즉 데스크가 'KICE-50' 이라고 치면 규칙의 'KICE_50' 과 안 맞아 **그 학생만
--   취소 판정에서 조용히 빠진다.** 오류가 나지 않고 검토 목록에 안 뜰 뿐이라
--   아무도 알아채지 못한다. 할인율도 학생마다 제각각 들어간다.
--
-- ★ 할인율을 여기 둔다.
--   같은 장학인데 학생마다 다른 할인율이 들어가면 퇴원 소급 재결제(0820 규정)가
--   "이 학생은 왜 40%였나"에 답할 수 없다. 예외 할인이 필요하면 마스터 행을 추가한다.
--
-- ⚠️ 마이그레이션에 실제 장학 종류를 시드하지 않는다.
--   지점·연도마다 다르고 확정 자료를 못 받았다. `penalty_rule`·`exam_form` 과 같은
--   방식이고, 비어 있으면 장학 부여가 SCHOLARSHIP_MASTER_NOT_FOUND 로 막힌다 —
--   그게 맞다. 임의값을 심으면 그 값으로 부여된 뒤 규칙과 어긋난다.
--   개발용 행은 db/seed/dev_seed_scholarship_master.sql 에 있다.

CREATE TABLE scholarship_master (
    id            BIGSERIAL    PRIMARY KEY,

    -- NULL = 전 지점 공통. 지점 행이 있으면 그 지점에서는 그것만 쓴다
    academy_id    BIGINT       REFERENCES academy (id),
    year          SMALLINT     NOT NULL,

    -- ★ scholarship.scholarship_type / scholarship_cancel_rule.scholarship_type 과
    --   같은 값이다. 세 곳이 이 코드로 이어진다
    code          VARCHAR(20)  NOT NULL,
    name          VARCHAR(50)  NOT NULL,

    discount_rate NUMERIC(5,2) NOT NULL CHECK (discount_rate >= 0 AND discount_rate <= 100),

    -- 지난 연도 장학을 지우지 않고 내린다 — 지우면 그 장학으로 부여된 이력의 근거가 끊긴다
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    SMALLINT     NOT NULL DEFAULT 0,
    memo          VARCHAR(200),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- COALESCE 로 묶는다 — NULL 은 유니크 비교에서 서로 다르게 취급돼
-- 공통 행이 같은 코드로 몇 개든 들어간다
CREATE UNIQUE INDEX uq_scholarship_master_code
    ON scholarship_master (year, code, COALESCE(academy_id, 0)) WHERE is_deleted = FALSE;

CREATE INDEX idx_scholarship_master_scope
    ON scholarship_master (year, academy_id, sort_order) WHERE is_deleted = FALSE;

COMMENT ON TABLE scholarship_master IS
    '장학 종류 마스터. code 가 scholarship·scholarship_cancel_rule 을 잇는 키다';
COMMENT ON COLUMN scholarship_master.code IS
    '★ scholarship_cancel_rule.scholarship_type 과 정확히 같아야 취소 판정이 걸린다';
COMMENT ON COLUMN scholarship_master.discount_rate IS
    '이 장학의 할인율. 부여 시 이 값을 복사한다 — 학생별로 다르게 넣지 않는다';
