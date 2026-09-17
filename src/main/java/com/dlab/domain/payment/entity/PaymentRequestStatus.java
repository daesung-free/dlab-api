package com.dlab.domain.payment.entity;

/**
 * 결제 요청 상태.
 *
 * <p>★ {@link #CREATED} 는 <b>링크가 만들어졌다</b>는 뜻이지 결제됐다는 뜻이 아니다.
 * KCP 가이드가 명시한다 — <i>"URL 생성 응답만으로 주문처리 하지 말 것"</i>.
 * 완료는 Webhook 이 확정한다.
 */
public enum PaymentRequestStatus {
    /** 결제 URL 생성됨. 고객이 아직 결제하지 않았다 */
    CREATED,
    /** 승인 완료 — Webhook 으로 확정됨 */
    PAID,
    /** 우리가 링크를 사용중지했거나 승인이 취소됨 */
    CANCELED,
    /** 링크 유효기간이 지남 */
    EXPIRED,
    /** 승인 실패. 사유는 {@code failReason} */
    FAILED
}
