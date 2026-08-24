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

    /**
     * 이용 연·월. <b>환불 계산이 이 값으로 교습일수를 찾는다.</b>
     *
     * <p>청구 1건 = 한 달분이다. 최초 입학 때 다음 달까지 함께 받는 경우는 청구를 2건
     * 만든다 — 한 건에 두 달을 담으면 <b>그중 한 달만 환불하는 계산이 성립하지 않는다.</b>
     *
     * <p>급식·특강은 달 단위가 아니라 비어 있을 수 있다.
     */
    @Column(name = "service_year")
    private Short serviceYear;

    @Column(name = "service_month")
    private Short serviceMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingStatus status = BillingStatus.PENDING;

    @OneToMany(mappedBy = "billing", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaymentTransaction> transactions = new ArrayList<>();

    /**
     * 항목(교습비·독서실비). <b>환불 산식이 항목마다 달라서</b> 나눠 든다.
     *
     * <p>비어 있을 수 있다 — 급식·특강처럼 나눌 것이 없는 청구다.
     * 그때는 청구 금액이 곧 단일 항목이라 봐도 된다.
     */
    @OneToMany(mappedBy = "billing", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<BillingItem> items = new ArrayList<>();

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

    /** 이용 월 지정. 환불 계산이 교습일수를 찾을 때 쓴다. */
    public void assignServicePeriod(short serviceYear, int serviceMonth) {
        this.serviceYear = serviceYear;
        this.serviceMonth = (short) serviceMonth;
    }

    /**
     * 항목 추가.
     *
     * <p><b>항목 합계가 청구 금액과 맞는지는 호출자가 보장한다.</b> 여기서 청구 금액을
     * 항목에서 다시 계산하지 않는 이유는, 이미 발행된 청구의 금액이 항목을 고칠 때마다
     * 흔들리면 <b>키오스크 영수증이 조용히 바뀌기</b> 때문이다.
     */
    public BillingItem addItem(BillingItemType itemType, int suppliedAmount, int discountAmount) {
        BillingItem item = new BillingItem(this, itemType, suppliedAmount, discountAmount,
                items.size() + 1);
        items.add(item);
        return item;
    }

    public List<BillingItem> activeItems() {
        return items.stream().filter(i -> !i.isDeleted()).toList();
    }

    /**
     * 항목 합계가 청구액과 맞는가. <b>어긋나면 환불 계산이 틀린다</b> —
     * 항목 기준으로 차감하는데 실제로 받은 돈은 청구액이기 때문이다.
     */
    public boolean itemsMatchBilledAmount() {
        List<BillingItem> active = activeItems();
        return active.isEmpty()
                || active.stream().mapToInt(BillingItem::getBilledAmount).sum() == billedAmount;
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
