package com.dlab.domain.payment.entity;

/** 현금영수증 상태. 취소해도 행은 남는다 — 신고 내역과 대조해야 한다. */
public enum CashReceiptStatus {
    ISSUED,
    CANCELED,
    FAILED
}
