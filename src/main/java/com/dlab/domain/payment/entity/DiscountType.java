package com.dlab.domain.payment.entity;

/**
 * 할인을 어떻게 적는가.
 *
 * <h2>★ 비율과 금액을 한 칸에 담지 않는다</h2>
 * 한 칸이면 {@code 30} 이 <b>「30%」인지 「30원」인지 알 수 없다.</b> 1016 이 「비율/금액
 * 구분」을 물은 것이 그래서다 — 둘이 섞여 있다는 뜻으로 읽힌다.
 */
public enum DiscountType {

    /** 퍼센트(0~100). 0820 규정의 10·20·30·50·70% 가 이쪽이다. */
    RATE,

    /** 원 단위 정액. */
    AMOUNT;

    /**
     * 정가에서 깎을 금액.
     *
     * <p>★ <b>정가보다 많이 깎지 않는다.</b> 금액 할인이 정가를 넘으면 청구액이 음수가 되고,
     * 그 값이 환불 계산까지 흘러간다 — 0820 의 「정상가 차감」이 음수를 다루는 것과는
     * 별개 문제다(그쪽은 추가 징수라는 뜻이 있지만 이쪽은 그냥 잘못된 입력이다).
     *
     * <p>비율은 <b>버림</b>이다. 0820 절사 규칙(`Rounding.FLOOR`)과 같다 —
     * 올림하면 할인이 규정보다 커진다.
     */
    public int amountOf(int suppliedAmount, int value) {
        int discount = this == RATE ? (int) ((long) suppliedAmount * value / 100) : value;
        return Math.min(discount, suppliedAmount);
    }
}
