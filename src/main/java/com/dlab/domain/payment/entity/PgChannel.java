package com.dlab.domain.payment.entity;

/**
 * 결제 채널. 사이트코드가 채널별로 따로 발급된다.
 */
public enum PgChannel {
    /**
     * 바이링크 — 결제 URL 을 만들어 문자로 보낸다.
     *
     * <p>KCP 가 문자까지 대신 보낸다({@code direct_send=Y}) — 우리 문자 업체가 아직
     * 정해지지 않았어도 결제 링크는 나간다.
     */
    BUYLINK,

    /** 데스크 카드단말기(POS). 단말이 직접 승인하고 우리는 결과를 받는다 */
    TERMINAL
}
