package com.dlab.domain.payment.entity;

/**
 * 청구 항목. <b>환불 산식이 여기서 갈린다</b>(0820 규정).
 *
 * <p>같은 750,000원 청구 안에서도 교습비와 독서실비는 되돌리는 방법이 다르다.
 * 한 값으로 합쳐 두면 퇴원 정산에서 다시 가를 방법이 없어서 나눈다.
 */
public enum BillingItemType {

    /**
     * 교습비 — <b>구간 환불</b>. 이용기간 1/3까지 2/3, 1/2까지 1/2, 1/2 이후 없음.
     * 학원법 반환기준 그대로다. 할인은 여기에만 붙는다.
     */
    TUITION(RefundMethod.BRACKET),

    /**
     * 독서실비 — <b>일할 환불</b>. 사용한 일수만큼 차감한다.
     * <b>할인이 없다</b> — 할인액이 0이 아니면 규정 위반이다.
     */
    STUDY_ROOM(RefundMethod.DAILY),

    /** 급식비 — 날짜·끼니 단위 취소라 이 계산을 타지 않는다. */
    MEAL(RefundMethod.NONE),

    /** 특강비. 환불 규정 미확정. */
    LECTURE(RefundMethod.NONE),

    /**
     * 등록비 — 입학 시 1회. F-4.8-1이 "카드·가상계좌·등록비 통합 매출"을 요구한다.
     *
     * <p>환불이 {@link RefundMethod#NONE}인 것은 <b>규정을 아직 못 받았기 때문</b>이지
     * 환불이 없다는 뜻이 아니다. 0820 규정은 교습비·독서실비만 다룬다 —
     * 받으면 여기 산식을 붙인다.
     */
    REGISTRATION(RefundMethod.NONE),

    ETC(RefundMethod.NONE);

    private final RefundMethod refundMethod;

    BillingItemType(RefundMethod refundMethod) {
        this.refundMethod = refundMethod;
    }

    public RefundMethod refundMethod() {
        return refundMethod;
    }

    /** 퇴원 정산 대상인가. {@link RefundMethod#NONE}은 별도 경로로 처리된다. */
    public boolean isWithdrawalRefundable() {
        return refundMethod != RefundMethod.NONE;
    }

    /** 할인이 붙을 수 있는 항목인가. 독서실비는 규정상 할인이 없다. */
    public boolean isDiscountable() {
        return this == TUITION || this == LECTURE || this == ETC;
    }

    /** 환불 산식. */
    public enum RefundMethod {
        /** 구간 — 1/3까지 2/3, 1/2까지 1/2, 이후 없음. */
        BRACKET,
        /** 일할 — 사용한 일수만큼 차감. */
        DAILY,
        /** 퇴원 정산 대상 아님. */
        NONE
    }
}
