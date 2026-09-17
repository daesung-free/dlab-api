package com.dlab.domain.payment.entity;

/**
 * 현금영수증 발행 용도. <b>식별번호의 의미를 바꾼다.</b>
 */
public enum ReceiptPurpose {
    /** 소득공제(개인). 식별번호는 <b>휴대폰번호</b> */
    PERSONAL("0"),
    /** 지출증빙(기업). 식별번호는 <b>사업자번호</b> */
    BUSINESS("1");

    private final String code;

    ReceiptPurpose(String code) {
        this.code = code;
    }

    /** KCP {@code tr_code}. 규격값이라 바꾸지 말 것 */
    public String code() {
        return code;
    }
}
