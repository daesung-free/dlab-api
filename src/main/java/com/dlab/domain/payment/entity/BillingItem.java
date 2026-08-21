package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 청구 항목 — 교습비 / 독서실비 (0820 규정).
 *
 * <h2>★ 청구를 쪼개는 게 아니라 아래에 다는 것이다</h2>
 * 750,000원을 두 청구로 나누면 키오스크 영수증({@code getReceiptInfo})이 한 줄에서
 * 두 줄로 바뀐다. 청구는 그대로 두고 항목만 달면 <b>영수증은 안 바뀌고 환불 계산만
 * 항목 단위로 돈다.</b>
 *
 * <h2>★ 정가를 항목마다 들고 있어야 한다</h2>
 * 환불 차감이 <b>"정상가 기준"</b>이라(규정 명시), 할인받은 학생의 차감액을 계산하려면
 * 할인 전 금액이 필요하다. 청구 합계의 정가만으로는 교습비 몫을 되짚을 수 없다.
 */
@Getter
@Entity
@Table(name = "billing_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_id", nullable = false)
    private Billing billing;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private BillingItemType itemType;

    /** 정가. 할인 전 금액이고 <b>환불 차감의 기준</b>이다. */
    @Column(name = "supplied_amount", nullable = false)
    private int suppliedAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    /** 정가 − 할인. 실제로 받은 금액이다. */
    @Column(name = "billed_amount", nullable = false)
    private int billedAmount;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    BillingItem(Billing billing, BillingItemType itemType, int suppliedAmount,
                int discountAmount, int sortOrder) {
        this.billing = billing;
        this.itemType = itemType;
        this.suppliedAmount = suppliedAmount;
        // ★ 독서실비에 할인이 들어오면 무시한다. 규정상 할인이 없고,
        //   통과시키면 환불 차감이 정가보다 작아져 학원이 손해를 본다
        this.discountAmount = itemType.isDiscountable() ? discountAmount : 0;
        this.billedAmount = Math.max(0, suppliedAmount - this.discountAmount);
        this.sortOrder = (short) sortOrder;
    }

    /** 할인을 받은 항목인가. 소급 재결제 판정이 쓴다. */
    public boolean isDiscounted() {
        return discountAmount > 0;
    }
}
