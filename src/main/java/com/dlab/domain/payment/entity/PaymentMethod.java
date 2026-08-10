package com.dlab.domain.payment.entity;

/** 수납 수단. 자체 PG(E-3) 연동 전까지는 현금·계좌이체 수기 기록이 주로 쓰인다. */
public enum PaymentMethod {
    CARD,
    /** 가상계좌. */
    VBANK,
    CASH,
    TRANSFER,
    ETC
}
