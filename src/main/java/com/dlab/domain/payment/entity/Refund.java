package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.payment.service.RefundCalculator;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환불 1건 — <b>계산 결과와 집행 결과를 함께</b> 남긴다.
 *
 * <h2>★ 계산기만으로는 부족했다</h2>
 * {@link RefundCalculator} 는 금액을 내지만 <b>어디에도 남기지 않았다.</b> 그래서
 * 「이 학생에게 얼마를 왜 돌려줬나」에 답할 수 없었고, 수납 취소는 우리 DB 에
 * 취소 표시만 하고 <b>PG 에는 아무 일도 일어나지 않았다.</b>
 *
 * <h2>⚠️ {@code refundAmount} 가 음수일 수 있다 — 추가 징수다</h2>
 * 0820 규정이 <b>정상가 기준 차감</b>이라, 할인받은 학생은 차감액이 납부액을 넘는다.
 * <b>0 에서 자르지 않는다</b> — 자르면 받아야 할 돈이 사라지고 데스크는 화면에서
 * 그 사실을 알 방법이 없다.
 *
 * <h2>⚠️ 환불계좌를 여기 두지 않는다</h2>
 * 「앱에서 직접 입력」으로 확정됐지만(10/12 확인 13) 계좌번호를 우리가 보관하게 되므로
 * <b>개인정보 동의 항목에 「환불계좌」가 들어가야 한다.</b> 그 문구가 미회신이라
 * 수집을 시작하지 않는다 — 약관은 수정이 아니라 <b>버전을 올리는 구조</b>라
 * 동의 없이 받아두면 되돌릴 수 없다.
 */
@Getter
@Entity
@Table(name = "refund")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_id", nullable = false)
    private Billing billing;

    /** 계산 입력 — 나중에 금액을 재현하려면 입력도 남아야 한다. */
    @Column(name = "teaching_days", nullable = false)
    private short teachingDays;

    @Column(name = "used_days", nullable = false)
    private short usedDays;

    /** 환불액. <b>음수면 추가 징수</b>다 — 위 참고. */
    @Column(name = "refund_amount", nullable = false)
    private int refundAmount;

    /** 항목별 계산 근거. 「추가결제 상세 내역 출력」(클라이언트 요청)이 이걸 쓴다. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundPayout method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    /** 카드 취소를 건 거래. 가상계좌 환불이면 비어 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private PaymentTransaction transaction;

    @Column(name = "pg_canceled_tid", length = 100)
    private String pgCanceledTid;

    @Column(name = "processed_at")
    private Instant processedAt;

    public Refund(Academy academy, short year, Billing billing,
                  RefundCalculator.Result result, RefundPayout method,
                  PaymentTransaction transaction) {
        this.academy = academy;
        this.year = year;
        this.billing = billing;
        this.teachingDays = (short) result.teachingDays();
        this.usedDays = (short) result.usedDays();
        this.refundAmount = result.refundAmount();
        this.detail = result.detail();
        this.method = method;
        this.transaction = transaction;
        // ★ 추가 징수는 「대기」가 아니다. 집행할 환불이 없고 받아야 할 돈이 있는 상태다
        this.status = result.requiresAdditionalPayment()
                ? RefundStatus.ADDITIONAL_PAYMENT
                : RefundStatus.PENDING;
    }

    /** 집행 완료 — 카드 취소 성공, 또는 이체를 끝냈다는 표시. */
    /**
     * ★ 재결제로 다시 받아야 하는 금액 (디랩 1029 7장).
     *
     * <p>★ 이 금액이 들어와야 <b>기결제 전체취소</b>를 걸 수 있다.
     */
    @Column(name = "repay_amount")
    private Integer repayAmount;

    /** 재결제 요청을 보낸 시각. ⚠️ 없으면 「보냈나 안 보냈나」가 구분되지 않는다. */
    @Column(name = "repay_requested_at")
    private Instant repayRequestedAt;

    /**
     * ★★ 재결제가 <b>확인된</b> 시각.
     *
     * <p>⚠️ <b>이 값이 있어야 기결제 전체취소를 걸 수 있다</b> — 먼저 취소하면
     * 학부모가 재결제를 안 할 때 <b>받을 돈이 사라진다.</b>
     */
    @Column(name = "repay_confirmed_at")
    private Instant repayConfirmedAt;

    /**
     * ★★ 재결제 요청을 보냈다 — <b>기결제는 아직 그대로다.</b>
     *
     * <p>★ 상태가 {@link RefundStatus#WAITING_REPAYMENT} 가 되어 화면에
     * <b>「재결제 대기」</b>로 뜬다.
     */
    public void waitRepayment(int amount, Instant at) {
        this.method = RefundPayout.REPAY_AND_FULL_CANCEL;
        this.repayAmount = amount;
        this.repayRequestedAt = at;
        this.status = RefundStatus.WAITING_REPAYMENT;
    }

    /**
     * ★★ 재결제가 <b>들어온 것을 확인</b>했다 — 이제 전체취소를 걸 수 있다.
     *
     * <p>★ <b>상태는 아직 바꾸지 않는다</b> — 취소가 성공해야 {@link #markDone} 이다.
     * ⚠️ 여기서 {@code DONE} 으로 바꾸면 <b>취소가 실패해도 끝난 것으로 보인다.</b>
     */
    public void confirmRepayment(Instant at) {
        this.repayConfirmedAt = at;
    }

    /** ★ 전체취소를 걸어도 되는가 — 재결제가 확인됐을 때만. */
    public boolean canFullCancel() {
        return method == RefundPayout.REPAY_AND_FULL_CANCEL && repayConfirmedAt != null;
    }

    public void markDone(String pgCanceledTid, Instant at) {
        this.status = RefundStatus.DONE;
        this.pgCanceledTid = pgCanceledTid;
        this.processedAt = at;
        this.failReason = null;
    }

    /**
     * 집행 실패.
     *
     * <p>★ <b>이력을 지우지 않는다.</b> 실패도 「시도했다」는 사실이라, 지우면
     * 「환불을 건 적이 없다」와 구분되지 않는다. 유니크 인덱스가 이 상태를 비켜 가므로
     * 고친 뒤 다시 걸 수 있다.
     */
    public void markFailed(String reason, Instant at) {
        this.status = RefundStatus.FAILED;
        this.failReason = reason;
        this.processedAt = at;
    }

    /** 추가 징수가 필요한가 — 데스크 화면이 안내를 가른다. */
    public boolean requiresAdditionalPayment() {
        return status == RefundStatus.ADDITIONAL_PAYMENT;
    }
}
