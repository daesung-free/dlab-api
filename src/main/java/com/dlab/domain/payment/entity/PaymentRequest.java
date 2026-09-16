package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 요청 한 건.
 *
 * <h2>왜 청구와 따로 두나</h2>
 * <b>결제는 즉시 끝나지 않는다.</b> 바이링크는 문자를 보낸 뒤 고객이 누를 때까지,
 * 가상계좌는 입금할 때까지 기다린다. {@code billing} 에는 미납·완납뿐이라
 * <b>"링크는 보냈는데 아직 안 낸"</b> 상태를 표현할 수 없고, 그러면 데스크가 링크를
 * 또 보내게 된다.
 *
 * <h2>★★ 완료는 Webhook 이 확정한다</h2>
 * 생성 응답이 성공이어도 그것은 <b>링크가 만들어졌다</b>는 뜻이다. KCP 가이드가
 * 명시한다 — <i>"URL 생성 응답만으로 주문처리 하지 말 것"</i>.
 *
 * <p>Webhook 은 우리가 {@code result=0000} 을 돌려줄 때까지 <b>최대 10번 재전송</b>된다.
 * 그래서 {@link #tno}(KCP 거래번호)에 유니크를 걸어 같은 승인이 두 번 수납되지 않게 한다 —
 * 두 번 잡히면 그 학생은 두 번 낸 것으로 기록된다.
 */
@Getter
@Entity
@Table(name = "payment_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRequest extends BaseEntity {

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pg_site_id", nullable = false)
    private PgSite pgSite;

    /** KCP 에 넘긴 우리 주문번호({@code ordr_idxx}). Webhook 이 이 값으로 돌아온다 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", nullable = false, length = 10)
    private PayMethod payMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentRequestStatus status = PaymentRequestStatus.CREATED;

    @Column(name = "pay_url", length = 500)
    private String payUrl;

    /** KCP 식별자. 거래조회·사용중지 서명에 쓴다({@code site_cd + "^" + url_reg_id}) */
    @Column(name = "url_reg_id", length = 100)
    private String urlRegId;

    @Column(name = "expire_at")
    private Instant expireAt;

    /** KCP 거래번호. 취소·조회의 키이고 Webhook 멱등의 근거다 */
    @Column(length = 20)
    private String tno;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "pay_detail", length = 200)
    private String payDetail;

    @Column(name = "fail_reason", length = 200)
    private String failReason;

    // ── 가상계좌 ─────────────────────────────────────────────
    @Column(name = "vbank_account", length = 30)
    private String vbankAccount;

    @Column(name = "vbank_bank_name", length = 30)
    private String vbankBankName;

    @Column(name = "vbank_bank_code", length = 10)
    private String vbankBankCode;

    /** 예금주. KCP 가 가맹점명으로 내려준다 */
    @Column(name = "vbank_depositor", length = 30)
    private String vbankDepositor;

    /**
     * 실제 입금자명.
     *
     * <p>⚠️ <b>예금주·학생명과 다를 수 있다</b> — 조부모가 대신 내는 경우가 흔하다.
     * 본인 확인 수단으로 쓰면 정상 입금을 반려하게 된다. 영수증·대사 참고용이다.
     */
    @Column(name = "vbank_remitter", length = 30)
    private String vbankRemitter;

    public PaymentRequest(Billing billing, PgSite pgSite, String orderNo, int amount,
                          PayMethod payMethod) {
        this.academy = billing.getAcademy();
        this.year = billing.getYear();
        this.billing = billing;
        this.pgSite = pgSite;
        this.orderNo = orderNo;
        this.amount = amount;
        this.payMethod = payMethod;
        this.status = PaymentRequestStatus.CREATED;
    }

    /**
     * 가상계좌 발급 반영.
     *
     * <p>⚠️ <b>발급은 결제가 아니다.</b> 계좌번호가 나왔을 뿐이고, 입금은 며칠 뒤에
     * 들어오거나 영영 안 들어온다.
     */
    public void markVbankIssued(String tno, String account, String bankName, String bankCode,
                                String depositor, Instant expireAt) {
        this.tno = tno;
        this.vbankAccount = account;
        this.vbankBankName = bankName;
        this.vbankBankCode = bankCode;
        this.vbankDepositor = depositor;
        this.expireAt = expireAt;
    }

    /** 입금자명 기록. 확정과 별개로 남긴다 — 누가 냈는지가 대사에 필요하다. */
    public void recordRemitter(String remitter) {
        this.vbankRemitter = remitter;
    }

    /** 생성 응답 반영. 아직 결제된 것이 아니다. */
    public void markUrlCreated(String payUrl, String urlRegId, Instant expireAt) {
        this.payUrl = payUrl;
        this.urlRegId = urlRegId;
        this.expireAt = expireAt;
    }

    /**
     * 승인 확정.
     *
     * <p><b>이미 승인된 건이면 아무 일도 하지 않는다</b> — Webhook 재전송이 같은 승인을
     * 여러 번 들고 온다. 여기서 두 번 처리하면 수납이 두 번 잡힌다.
     *
     * @return 이번 호출에서 처음 확정됐는지. 수납 기록은 {@code true} 일 때만 만든다
     */
    public boolean markPaid(String tno, Instant approvedAt, String payDetail) {
        if (status == PaymentRequestStatus.PAID) {
            return false;
        }
        this.status = PaymentRequestStatus.PAID;
        // 가상계좌는 발급 시점에 이미 tno 를 받았다. 입금 통보의 값과 같지만,
        // 비어 있을 때만 채워 발급 tno 를 덮어쓰지 않는다
        if (this.tno == null && tno != null) {
            this.tno = tno;
        }
        this.approvedAt = approvedAt;
        this.payDetail = payDetail;
        return true;
    }

    public void markFailed(String reason) {
        this.status = PaymentRequestStatus.FAILED;
        this.failReason = reason;
    }

    public void markCanceled() {
        this.status = PaymentRequestStatus.CANCELED;
    }

    public boolean isPaid() {
        return status == PaymentRequestStatus.PAID;
    }
}
