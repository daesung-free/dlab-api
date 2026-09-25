package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현금영수증 (I-25).
 *
 * <h2>발급 대상은 현금성 거래다</h2>
 * 계좌이체·가상계좌·무통장입금. <b>카드 결제는 발급하지 않는다</b> — 카드사가 이미
 * 소득공제를 처리하므로 이중이 된다.
 *
 * <h2>★ 용도에 따라 식별번호의 의미가 바뀐다</h2>
 * 소득공제(개인)면 <b>휴대폰번호</b>, 지출증빙(기업)이면 <b>사업자번호</b>다. 같은 칸에
 * 다른 것이 들어가므로 화면이 무엇을 받는지 분명히 해야 한다.
 *
 * <h2>공급가액·부가세를 저장한다</h2>
 * 나중에 다시 계산하지 않는다 — 절사 규칙이 바뀌면 발급한 값과 어긋나고, 국세청에
 * 신고된 것은 그때 보낸 값이다.
 */
@Getter
@Entity
@Table(name = "cash_receipt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashReceipt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_id", nullable = false)
    private Billing billing;

    /** 어느 수납에 대한 영수증인가. 수납을 취소하면 이것도 취소해야 한다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private PaymentTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pg_site_id", nullable = false)
    private PgSite pgSite;

    @Column(name = "order_no", nullable = false, length = 50)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_purpose", nullable = false, length = 10)
    private ReceiptPurpose purpose;

    @Column(name = "id_info", nullable = false, length = 19)
    private String idInfo;

    @Column(nullable = false)
    private int amount;

    @Column(name = "supply_amount", nullable = false)
    private int supplyAmount;

    @Column(name = "tax_amount", nullable = false)
    private int taxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CashReceiptStatus status = CashReceiptStatus.ISSUED;

    /** KCP 현금영수증 거래번호. 취소의 키다 */
    @Column(name = "cash_no", length = 20)
    private String cashNo;

    @Column(name = "receipt_no", length = 20)
    private String receiptNo;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    /**
     * 취소 승인번호.
     *
     * <p>★ <b>발급 승인번호와 다른 값이다.</b> 취소도 국세청에 "-" 매출로 등록되는 별개의
     * 건이라 승인번호가 새로 나온다 — 덮어쓰면 무엇을 신고했는지 대조할 수 없다.
     */
    @Column(name = "cancel_receipt_no", length = 20)
    private String cancelReceiptNo;

    @Column(name = "fail_reason", length = 200)
    private String failReason;

    public CashReceipt(Billing billing, PaymentTransaction transaction, PgSite pgSite,
                       String orderNo, ReceiptPurpose purpose, String idInfo,
                       int amount, int supplyAmount, int taxAmount) {
        this.academy = billing.getAcademy();
        this.year = billing.getYear();
        this.billing = billing;
        this.transaction = transaction;
        this.pgSite = pgSite;
        this.orderNo = orderNo;
        this.purpose = purpose;
        this.idInfo = idInfo;
        this.amount = amount;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
    }

    public void markIssued(String cashNo, String receiptNo, Instant issuedAt) {
        this.status = CashReceiptStatus.ISSUED;
        this.cashNo = cashNo;
        this.receiptNo = receiptNo;
        this.issuedAt = issuedAt;
    }

    public void markFailed(String reason) {
        this.status = CashReceiptStatus.FAILED;
        this.failReason = reason;
    }

    /**
     * 취소.
     *
     * <p><b>행을 지우지 않는다.</b> 발급했다가 취소한 사실이 남아야 국세청 신고 내역과
     * 대조할 수 있다 — 지우면 "발급한 적 없음" 과 구분되지 않는다.
     */
    public void markCanceled(String cancelReceiptNo, Instant canceledAt) {
        this.status = CashReceiptStatus.CANCELED;
        this.cancelReceiptNo = cancelReceiptNo;
        this.canceledAt = canceledAt;
    }

    public boolean isIssued() {
        return status == CashReceiptStatus.ISSUED;
    }
}
