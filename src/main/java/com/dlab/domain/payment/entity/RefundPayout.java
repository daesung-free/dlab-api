package com.dlab.domain.payment.entity;

/**
 * 환불을 <b>어떻게 지급하는가</b> (디랩 10/12 회신 ②).
 *
 * <p>★ 결제 수단에 따라 갈린다 — 회신 원문: <i>"카드 결제 → 카드 취소 /
 * 가상계좌 입금 → 계좌 이체."</i>
 *
 * <p>⚠️ {@link BillingItemType.RefundMethod}(구간/일할)와 <b>다른 축</b>이다.
 * 저쪽은 <b>얼마를</b> 돌려줄지, 이쪽은 <b>어떻게</b> 돌려줄지다 — 이름이 비슷해
 * 섞기 쉬우므로 {@code RefundMethod} 를 쓰지 않았다.
 */
public enum RefundPayout {
    /** 카드 결제분 — PG 부분취소를 건다. */
    CARD_CANCEL,
    /**
     * 가상계좌 입금분 — <b>계좌 이체 대상으로 적재만</b> 한다.
     *
     * <p>우리가 이체를 실행하지 않는다. 이체는 사람이 은행에서 하고, 우리는
     * <b>누구에게 얼마를 보내야 하는지</b>를 목록으로 들고 있는다.
     */
    BANK_TRANSFER,
    /** 돌려줄 것이 없다 — 환불액 0, 또는 추가 징수(음수)다. */
    NONE
}
