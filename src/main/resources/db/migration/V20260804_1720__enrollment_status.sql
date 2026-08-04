-- V20260804_1720: 학생 상태 5종 + 상태 변경 이력 (P1-04)
--
-- ① 제적(EXPELLED) 추가
--    실행가이드 Phase 1 체크리스트가 상태를 "재원/휴원/퇴원/제적/수료" 5종으로 정의하는데
--    V1의 CHECK는 4종이라 제적이 빠져 있었다.
--    퇴원(자진)과 제적(강제)은 후속처리가 같아도 구분해야 한다 — 재등록 심사, 환불 산정,
--    통계에서 다르게 취급되고, 한번 뭉뚱그리면 과거 건은 되살릴 수 없다.
--
-- ② 상태 변경 이력 테이블
--    student_enrollment은 현재 상태만 들고 있어 "누가·언제·왜 퇴원 처리했나"에 답할 수 없다.
--    created_by는 등록 시점 작성자라 이후 변경자를 남기지 못하고, @LastModifiedBy 컬럼도 없다.
--    상태 변경은 환불·재등록 분쟁으로 직결돼 근거가 남아야 하는 대표적 감사 대상이다.
--    나중에 붙이면 그 이전 기간은 영영 복구되지 않는다(CLAUDE.md §7).

ALTER TABLE student_enrollment
    DROP CONSTRAINT student_enrollment_enrollment_status_check;

ALTER TABLE student_enrollment
    ADD CONSTRAINT student_enrollment_enrollment_status_check
        CHECK (enrollment_status IN ('ENROLLED', 'ON_LEAVE', 'WITHDRAWN', 'EXPELLED', 'GRADUATED'));

COMMENT ON COLUMN student_enrollment.enrollment_status IS
    '재원 상태 5종. account.status(가입 승인)와 별개 축이다. 변경 이력은 enrollment_status_history.';


CREATE TABLE enrollment_status_history (
    id            BIGSERIAL   PRIMARY KEY,
    -- 지점별 조회(퇴원 사유 집계 등)가 잦아 비정규화해 둔다. enrollment을 join하면 나오지만
    -- 목록 조회마다 join이 붙는다.
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    -- year 없음 — enrollment이 이미 연도를 내포한다(V1 머리말 규칙).
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    from_status   VARCHAR(20) NOT NULL
                  CHECK (from_status IN ('ENROLLED', 'ON_LEAVE', 'WITHDRAWN', 'EXPELLED', 'GRADUATED')),
    to_status     VARCHAR(20) NOT NULL
                  CHECK (to_status IN ('ENROLLED', 'ON_LEAVE', 'WITHDRAWN', 'EXPELLED', 'GRADUATED')),
    -- 처리한 날이 아니라 효력이 발생한 날. 소급 처리가 실제로 있다
    -- (지난달에 그만뒀는데 이번 달에 입력) — 환불 일할계산이 이 날짜를 쓴다.
    effective_date DATE       NOT NULL,
    reason        VARCHAR(500),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 변경 주체. SecurityAuditorAware가 채운다(배치는 시스템 계정 0).
    created_by    BIGINT,
    -- 공통 규칙상 두지만 이력행은 지우지 않는다. 지우면 이력을 남긴 의미가 없다.
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,

    -- 같은 상태로의 전이는 이력이 아니다. 서비스에서도 막지만 DB에서도 막는다.
    CONSTRAINT ck_status_history_changed CHECK (from_status <> to_status)
);

CREATE INDEX idx_status_history_enrollment
    ON enrollment_status_history (enrollment_id, created_at DESC);
CREATE INDEX idx_status_history_academy
    ON enrollment_status_history (academy_id, effective_date DESC);

COMMENT ON TABLE enrollment_status_history IS
    '학생 재원 상태 변경 이력. 환불·재등록 분쟁의 근거 자료라 물리 삭제하지 않는다.';
COMMENT ON COLUMN enrollment_status_history.effective_date IS
    '효력 발생일. 처리일(created_at)과 다를 수 있다 — 소급 처리가 실제로 있다.';
