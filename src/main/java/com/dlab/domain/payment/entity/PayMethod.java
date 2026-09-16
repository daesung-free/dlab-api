package com.dlab.domain.payment.entity;

/**
 * KCP 결제수단({@code pay_method}). <b>KCP 규격 값이라 이름을 바꾸지 말 것</b> —
 * 그대로 전문에 실린다.
 *
 * <p>{@link PaymentMethod}(우리 수납 방식)와 다르다. 이쪽은 <b>KCP 에 무엇으로 요청하는가</b>이고,
 * 저쪽은 <b>실제로 어떻게 받았는가</b>다 — 현금·계좌이체처럼 KCP 를 안 거치는 수납이 있어
 * 합칠 수 없다.
 */
public enum PayMethod {
    /** 신용카드 */
    CARD,
    /** 계좌이체 */
    BANK,
    /** 휴대폰 결제 */
    MOBX,
    /** 가상계좌. 발급만 하고 입금은 나중에 들어온다 */
    VCNT
}
