package com.dlab.domain.payment.entity;

/**
 * 청구·수납 상태 — <b>등록비·급식 공통 라이프사이클</b>(시트 확정).
 *
 * <p>{@code 청구생성 → 결제분기(카드/가상계좌) → 수납반영 → 미납추출 → 환불}.
 * 결제(E-3)가 붙기 전까지는 {@link #PENDING}과 {@link #PAID}만 쓴다 —
 * 수기 수납을 기록하면 바로 완납이 된다.
 */
public enum BillingStatus {
    /** 청구 생성됨. 미납이다. */
    PENDING,
    /** 가상계좌 발급됨. */
    ISSUED,
    /** 완납. */
    PAID,
    CANCELLED,
    /** 가상계좌 입금 기한 만료. */
    EXPIRED,
    /**
     * 환불 완료.
     *
     * <p><b>산출은 아직 못 한다</b> — 일할계산 산식(I-26)이 미확정이고 학원법
     * 반환기준이라 PG 취소 API 호출로 대체할 수 없다. 상태만 미리 둔다.
     */
    REFUNDED
}
