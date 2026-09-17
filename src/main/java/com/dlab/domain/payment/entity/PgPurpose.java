package com.dlab.domain.payment.entity;

/**
 * 결제 용도. <b>사이트코드를 가르는 축</b>이다.
 *
 * <p>급식은 <b>업체 명의</b>로 결제된다(디온푸드·한샘푸드 …). 학원 코드로 받으면 그 돈이
 * 업체에게 가지 않고, 정산·환불 주체도 어긋난다.
 */
public enum PgPurpose {
    /** 교습비·독서실비. 학원(대성학력개발) 명의 */
    TUITION,
    /** 급식비. 급식업체 명의 */
    MEAL
}
