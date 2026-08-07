package com.dlab.domain.payment.entity;

/**
 * 청구 유형.
 *
 * <p>등록비와 급식이 <b>같은 PG·같은 라이프사이클</b>을 쓴다(0803 확정).
 * 시트가 전표번호에 유형 구분자를 넣을 것을 권장해 여기서 갈라둔다.
 */
public enum BillingType {
    /** 등록비·교습비. */
    TUITION,
    /** 급식비. */
    MEAL,
    /** 특강비. */
    LECTURE,
    ETC
}
