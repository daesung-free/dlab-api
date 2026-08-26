-- V20260826_1400: 할인 소급 재결제 (0820 규정 / 2026-08-26 판정 확정)
--
-- ★ 퇴원할 때 그동안 받은 할인을 정상가로 "다시 받는" 것이다. 환불이 아니라 징수다.
--
-- ★ 판정 기준 (클라이언트 확인 완료)
--     퇴원하는 달이 **할인 기간 안**이면 → 그동안 받은 할인을 전부 정상가로 재결제
--     할인이 끝나고 **정상가로 다니다** 퇴원하면 → 소급 없음
--
--   규정 예시가 이 판정으로 그대로 재현된다:
--     · 3~5월 할인 + 6월 정상가 연장 → 6월 퇴원 시 소급 없음   (6월 청구가 정상가)
--     · 3~5월 할인 → 5월 퇴원 시 전부 정상가 재결제           (5월 청구가 할인)
--
--   즉 "할인 기간 안"인지는 **퇴원한 달의 청구가 할인을 받았는지**로 판정한다.
--   별도 기간 테이블을 두지 않는다 — 청구가 이미 그 사실을 들고 있고,
--   기간을 따로 관리하면 청구와 어긋났을 때 어느 쪽이 진실인지 알 수 없다.
--
-- ★ 퇴원한 달은 여기서 다루지 않는다 — 환불 계산(RefundCalculator)이 이미 처리한다.
--   그 달은 "납부액 − 정가 × 차감비율"로 계산되고, 할인받았으면 그 결과가 음수(=추가 징수)다.
--   여기에 또 넣으면 **같은 달을 두 번 받는다.**
--   소급 대상은 **퇴원한 달보다 앞선 달들**뿐이다.

CREATE TABLE retroactive_charge (
    id                 BIGSERIAL   PRIMARY KEY,
    academy_id         BIGINT      NOT NULL REFERENCES academy (id),
    year               SMALLINT    NOT NULL,

    -- 새로 만든 소급 청구. 헤더(총액·날짜)는 billing 이 들고 있으므로 여기 다시 두지 않는다
    charge_billing_id  BIGINT      NOT NULL REFERENCES billing (id),
    -- 소급 대상이 된 원 청구. 행 하나가 곧 "그 달 내역"이다
    source_billing_id  BIGINT      NOT NULL REFERENCES billing (id),

    service_year       SMALLINT    NOT NULL,
    service_month      SMALLINT    NOT NULL CHECK (service_month BETWEEN 1 AND 12),

    -- 그 달 교습비 정가 / 실제 납부액 / 차액(=소급분)
    supplied_amount    INTEGER     NOT NULL CHECK (supplied_amount >= 0),
    paid_amount        INTEGER     NOT NULL CHECK (paid_amount >= 0),
    charge_amount      INTEGER     NOT NULL CHECK (charge_amount >= 0),

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         BIGINT,
    is_deleted         BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 같은 청구를 두 번 소급하면 학생이 두 번 낸다
CREATE UNIQUE INDEX uq_retroactive_charge_source
    ON retroactive_charge (source_billing_id) WHERE is_deleted = FALSE;

CREATE INDEX idx_retroactive_charge_billing
    ON retroactive_charge (charge_billing_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE retroactive_charge IS
    '할인 소급 재결제 내역. 행 하나가 "어느 달 할인을 얼마나 되받는가"다';
COMMENT ON COLUMN retroactive_charge.charge_amount IS
    '정가 − 납부액. 독서실비는 할인이 없어 교습비 항목만 대상이다';
