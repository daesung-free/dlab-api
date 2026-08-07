package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수납 거래.
 *
 * <p>청구 1건에 <b>여러 건이 붙는다</b>(분납·부분입금).
 * 취소해도 지우지 않는다 — 수납 이력이 사라지면 정산 추적이 끊긴다.
 */
@Getter
@Entity
@Table(name = "payment_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_id", nullable = false)
    private Billing billing;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    /** PG 거래 식별자. 자체 PG 연동(E-3) 전까지는 비어 있다. */
    @Column(name = "pg_tid", length = 100)
    private String pgTid;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    PaymentTransaction(Billing billing, int amount, PaymentMethod method, Instant paidAt) {
        this.billing = billing;
        this.amount = amount;
        this.method = method;
        this.paidAt = paidAt;
    }

    public boolean isActive() {
        return canceledAt == null && !isDeleted();
    }

    public void cancel(Instant at) {
        this.canceledAt = at;
    }
}
