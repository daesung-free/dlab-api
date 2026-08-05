-- V20260805_1400: 급식 신청 기록
--
-- ★ 왜 지금 만드나 — 이게 없으면 급식이 전부 뚫린다.
--   키오스크는 getMealApplyYN 호출이 실패하면 **전부 허용**으로 폴백한다
--   (그쪽 DsaMealService에 return true가 4군데). 우리가 이 엔드포인트를 안 만들면
--   에러 화면조차 안 뜨고 신청 안 한 학생이 그대로 배식받는다.
--   즉 "아직 급식 도메인이 없다"가 안전한 상태가 아니라 가장 위험한 상태다.
--
-- ★ 범위 — 신청 기록만이다. 결제·환불·정산은 없다.
--   PG 스펙(E-3)·데스크 당일신청 결제방식(I-13)이 미확정이라 그쪽은 못 만든다.
--   신청/취소가 있고 월별 현황을 본다는 것만 확정이라(F-4.5) 거기까지만 만든다.
--   payment 도메인이 생기면 이 테이블에 결제 참조를 붙이면 된다.

CREATE TABLE meal_application (
    id            BIGSERIAL   PRIMARY KEY,
    academy_id    BIGINT      NOT NULL REFERENCES academy (id),
    -- year 없음 — enrollment이 이미 연도를 내포한다(V1 머리말 규칙)
    enrollment_id BIGINT      NOT NULL REFERENCES student_enrollment (id),

    meal_date     DATE        NOT NULL,
    meal_type     VARCHAR(10) NOT NULL CHECK (meal_type IN ('LUNCH', 'DINNER')),

    -- ★ 취소를 물리 삭제하지 않는다. 앱 취소는 3일 전 PG 환불 대상이고
    --   관리자 취소는 기간 제한이 없어(F-4.5), "언제 누가 취소했나"가 정산 근거가 된다.
    canceled_at   TIMESTAMPTZ,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 끼니를 두 번 신청할 수 없다. 취소분은 제외하므로 취소 후 재신청은 된다.
CREATE UNIQUE INDEX uq_meal_application_active
    ON meal_application (enrollment_id, meal_date, meal_type)
    WHERE canceled_at IS NULL AND NOT is_deleted;

-- getMealApplyStdInfo(월별 지점 전체)가 이 순서로 훑는다
CREATE INDEX idx_meal_application_lookup
    ON meal_application (academy_id, meal_date)
    WHERE canceled_at IS NULL AND NOT is_deleted;

COMMENT ON TABLE meal_application IS
    '급식 신청. 결제는 payment 도메인 확정 후 연결(E-3·I-13 대기)';
COMMENT ON COLUMN meal_application.canceled_at IS
    '취소 시각. 물리 삭제하지 않는다 — 환불·정산의 근거다';
