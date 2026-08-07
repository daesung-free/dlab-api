package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 청구 — "누구에게 얼마를 받을 것인가" (F-4.8-1 · 키오스크 3.29).
 *
 * <h2>수납액을 컬럼으로 두지 않는다</h2>
 * 거래({@link PaymentTransaction})에서 합산한다. 컬럼으로 두면 거래 합계와 어긋났을 때
 * <b>어느 쪽이 맞는지 알 수 없다</b> — 정산에서 가장 답하기 어려운 종류의 문제다.
 *
 * <h2>청구액을 저장한다</h2>
 * {@code billedAmount = suppliedAmount − discountAmount}를 계산해서 넣는다.
 * 매번 다시 계산하면 <b>할인 정책이 바뀔 때 과거 청구액이 소급해서 바뀐다.</b>
 */
@Getter
@Entity
@Table(name = "billing")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Billing extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** 키오스크 {@code rcv_nm}. */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type", nullable = false, length = 20)
    private BillingType billingType;

    /** 정가. 키오스크 {@code supp_amt}. */
    @Column(name = "supplied_amount", nullable = false)
    private int suppliedAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    /** 정가 − 할인. 미납 계산의 기준이다. */
    @Column(name = "billed_amount", nullable = false)
    private int billedAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingStatus status = BillingStatus.PENDING;

    @OneToMany(mappedBy = "billing", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaymentTransaction> transactions = new ArrayList<>();

    public Billing(StudentEnrollment enrollment, String name, BillingType billingType,
                   int suppliedAmount, int discountAmount, LocalDate dueDate) {
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.enrollment = enrollment;
        this.name = name;
        this.billingType = billingType;
        this.suppliedAmount = suppliedAmount;
        this.discountAmount = discountAmount;
        this.billedAmount = Math.max(0, suppliedAmount - discountAmount);
        this.dueDate = dueDate;
        this.status = BillingStatus.PENDING;
    }

    /** 수납 합계. 취소된 거래는 빼고 센다. */
    public int receivedAmount() {
        return transactions.stream()
                .filter(PaymentTransaction::isActive)
                .mapToInt(PaymentTransaction::getAmount)
                .sum();
    }

    /** 미납액. 키오스크 {@code mi_amt}. 과납이어도 음수로 내리지 않는다. */
    public int unpaidAmount() {
        return Math.max(0, billedAmount - receivedAmount());
    }

    /**
     * 수납 기록.
     *
     * <p>완납되면 상태를 {@link BillingStatus#PAID}로 올린다 — 미납자 추출이 상태를 본다.
     */
    public PaymentTransaction addPayment(int amount, PaymentMethod method,
                                         java.time.Instant paidAt) {
        PaymentTransaction tx = new PaymentTransaction(this, amount, method, paidAt);
        transactions.add(tx);
        refreshStatus();
        return tx;
    }

    /** 거래가 취소되면 다시 미납으로 내려가야 한다. */
    public void refreshStatus() {
        if (status == BillingStatus.CANCELLED || status == BillingStatus.REFUNDED) {
            return;
        }
        this.status = unpaidAmount() == 0 ? BillingStatus.PAID : BillingStatus.PENDING;
    }

    public void cancel() {
        this.status = BillingStatus.CANCELLED;
    }
}
