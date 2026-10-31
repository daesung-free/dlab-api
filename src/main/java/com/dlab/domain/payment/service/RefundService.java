package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.PaymentTransaction;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.Refund;
import com.dlab.domain.payment.entity.RefundPayout;
import com.dlab.domain.payment.entity.RefundStatus;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.domain.payment.repository.RefundRepository;
import com.dlab.integration.pg.KcpBuyLinkClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 집행 (0820 규정 + 디랩 10/12 회신 ①②③).
 *
 * <h2>★★ 지금까지 「취소」가 우리 DB 에만 남았다</h2>
 * {@code BillingService.cancelPayment} 는 거래에 취소 표시만 하고 <b>PG 에 아무것도
 * 보내지 않았다.</b> 실연동 뒤에 그 상태면 <b>「취소했는데 환불이 안 된」 건이 쌓인다.</b>
 *
 * <h2>★ 수단에 따라 갈린다 (10/12 회신 ②)</h2>
 * <ul>
 *   <li><b>카드 결제분</b> → PG 부분취소를 건다. 전체 취소가 아니다 —
 *       교습비 환불은 구간 계산으로 <b>일부만</b> 돌려준다(750,000 받고 375,000 환불)</li>
 *   <li><b>가상계좌 입금분</b> → <b>계좌 이체 대상으로 적재만</b> 한다.
 *       우리가 이체하지 않는다(사람이 은행에서 한다)</li>
 * </ul>
 *
 * <h2>⚠️ 급식비는 이 경로를 타지 않는다</h2>
 * 급식은 <b>업체 명의 TID</b> 로 매출이 잡혀(0911 MID 구조), 학원비와 같은 경로로
 * 취소를 걸면 <b>남의 사업자 매출을 우리가 취소하는 꼴</b>이 된다. 처리 주체가
 * 미회신이라(확인 14) 명시적으로 거절한다 — 조용히 학원 코드로 걸면 정산이 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final BillingRepository billingRepository;
    private final PgSiteRepository pgSiteRepository;
    private final TuitionPricingService pricingService;
    private final KcpBuyLinkClient kcpClient;
    private final Clock clock;

    /**
     * 환불 <b>미리보기</b> — 저장하지 않는다.
     *
     * <p>★ 데스크가 <b>누르기 전에</b> 금액과 근거를 봐야 한다. 특히 추가 징수가
     * 나오는 경우는 「돌려주는」 화면에서 「더 받아야 한다」로 뒤집히므로,
     * 모르고 누르면 안 된다.
     */
    @Transactional(readOnly = true)
    public RefundCalculator.Result preview(AuthPrincipal me, Long billingId, int usedDays) {
        Billing billing = requireBilling(me, billingId);
        return calculate(billing, usedDays);
    }

    /**
     * 환불 집행.
     *
     * @param usedDays 이용한 교습일수. ⚠️ <b>서버가 추정하지 않는다</b> — 퇴원 효력일이
     *                 소급일 수 있고(0820 규정이 효력일을 쓴다) 그 판단은 데스크가 한다
     */
    @Transactional
    public Refund execute(AuthPrincipal me, Long billingId, int usedDays) {
        return execute(me, billingId, usedDays, false);
    }

    /**
     * ★★ <b>재결제 후 전체취소</b>를 고를 수 있는 집행 (디랩 1029 7장).
     *
     * <p>디랩 원문: <b>「부분취소, 재결제 후 전체취소 모두 필요 … 지점이나 상황에 따라
     * 골라서 사용할 수 있도록」</b>
     *
     * <h2>★★ 전체취소는 여기서 걸지 않는다</h2>
     * ① 재결제 금액을 적고 <b>{@link RefundStatus#WAITING_REPAYMENT}</b> 로 둔다<br>
     * ② 재결제가 확인되면 {@link #confirmRepaymentAndCancel} 이 전체취소를 건다
     *
     * <p>⚠️⚠️ <b>순서를 바꾸면 안 된다</b> — 학부모가 재결제를 안 하면
     * <b>받을 돈이 사라진다.</b> ★ 카드 정보를 보관하지 않으므로(빌링키 없음)
     * <b>서버가 혼자 재결제할 수 없다.</b>
     *
     * @param repayAndFullCancel ★ {@code true} 면 재결제 후 전체취소.
     *                           ⚠️ 가상계좌로 받은 건에는 쓸 수 없다
     */
    @Transactional
    public Refund execute(AuthPrincipal me, Long billingId, int usedDays,
                          boolean repayAndFullCancel) {
        Billing billing = requireBilling(me, billingId);

        // ★ 급식은 업체 명의 TID 다 — 여기서 걸러야 남의 사업자 매출을 건드리지 않는다
        if (billing.getBillingType() == BillingType.MEAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "급식비는 이 경로로 환불할 수 없습니다. 급식은 업체 명의로 매출이 잡혀 "
                            + "처리 주체가 정해져야 합니다.");
        }
        refundRepository.findActiveByBilling(billingId).ifPresent(r -> {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 환불이 처리된 청구입니다. (상태: %s)".formatted(r.getStatus()));
        });

        RefundCalculator.Result result = calculate(billing, usedDays);
        Optional<PaymentTransaction> card = latestActive(billing, PaymentMethod.CARD);
        Optional<PaymentTransaction> vbank = latestActive(billing, PaymentMethod.VBANK);

        RefundPayout payout;
        if (result.requiresAdditionalPayment() || result.refundAmount() == 0) {
            // 돌려줄 것이 없다. 추가 징수는 「취소」가 아니다 — PG 에 걸 것이 없다
            payout = RefundPayout.NONE;
        } else if (card.isPresent()) {
            payout = RefundPayout.CARD_CANCEL;
        } else if (vbank.isPresent()) {
            payout = RefundPayout.BANK_TRANSFER;
        } else {
            // 현금·계좌이체로 받은 건이다. PG 에 걸 것이 없으니 이체 대상으로 둔다
            payout = RefundPayout.BANK_TRANSFER;
        }

        // ★★ 재결제 후 전체취소를 고른 경우 (디랩 1029 7장).
        //
        //    ⚠️ 돌려줄 것이 있고 ★ 카드로 받은 건에만 쓸 수 있다 —
        //    ★ 가상계좌 건은 계좌 이체 환불이고(10/12 회신), 추가 징수 건은
        //    애초에 취소할 것이 없다.
        if (repayAndFullCancel) {
            if (payout != RefundPayout.CARD_CANCEL) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "재결제 후 전체취소는 카드로 결제된 건에만 쓸 수 있습니다. "
                                + "가상계좌·현금 건은 계좌 이체로 환불해 주세요.");
            }
            payout = RefundPayout.REPAY_AND_FULL_CANCEL;
        }

        Refund refund = refundRepository.save(new Refund(
                billing.getAcademy(), billing.getYear(), billing, result, payout,
                payout == RefundPayout.CARD_CANCEL
                        || payout == RefundPayout.REPAY_AND_FULL_CANCEL
                        ? card.orElse(null) : null));

        if (payout == RefundPayout.CARD_CANCEL) {
            cancelOnPg(refund, billing, card.get(), result.refundAmount());
        } else if (payout == RefundPayout.REPAY_AND_FULL_CANCEL) {
            // ★ 기결제 전액을 취소하고 ★ 다시 받을 금액 = 기결제 − 환불액
            int repay = card.get().getAmount() - result.refundAmount();
            refund.waitRepayment(repay, Instant.now(clock));
            log.info("★ 재결제 대기로 둠: billingId={}, 기결제={}, 환불={}, 재결제={}",
                    billingId, card.get().getAmount(), result.refundAmount(), repay);
        }

        log.info("환불 처리: billingId={}, 금액={}, 방식={}, 상태={}, 처리자={}",
                billingId, result.refundAmount(), payout, refund.getStatus(), me.accountId());
        return refund;
    }

    /**
     * 계좌 이체 완료 표시 — <b>사람이 이체한 뒤 누른다.</b>
     *
     * <p>★ 우리가 이체를 실행하지 않으므로 완료 여부를 알 방법이 없다. 표시를 안 두면
     * <b>대상 목록이 영원히 줄지 않아</b> 누가 받았는지 못 가린다.
     */
    @Transactional
    public Refund markTransferred(AuthPrincipal me, Long refundId) {
        Refund refund = refundRepository.findById(refundId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "환불 내역을 찾을 수 없습니다."));
        if (!me.canAccessAcademy(refund.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (refund.getMethod() != RefundPayout.BANK_TRANSFER) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "계좌 이체 대상이 아닙니다. (방식: %s)".formatted(refund.getMethod()));
        }
        refund.markDone(null, Instant.now(clock));
        return refund;
    }

    /** 계좌 이체 대상 목록 — 데스크가 매일 보는 자리다. */
    @Transactional(readOnly = true)
    public List<Refund> pendingTransfers(AuthPrincipal me, Long academyId, short year) {
        Long scope = me.requireAcademyScope(academyId);
        return refundRepository.findByStatus(scope, year, RefundStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<Refund> findAll(AuthPrincipal me, Long academyId, short year) {
        Long scope = me.requireAcademyScope(academyId);
        return refundRepository.findAllOf(scope, year);
    }

    // ───────────────────────────────────────────────

    /**
     * ★★ 재결제를 확인하고 <b>기결제를 전체취소</b>한다 (디랩 1029 7장 ②단계).
     *
     * <h2>⚠️ 재결제 확인이 먼저다</h2>
     * <b>전체취소를 먼저 하면 학부모가 재결제를 안 할 때 받을 돈이 사라진다.</b>
     * 그래서 이 경로는 <b>직원이 「재결제 들어왔다」를 누른 뒤</b>에만 돈다.
     *
     * <p>★ <b>취소가 성공해야 {@code DONE}</b> 이다 — 확인 시점에 상태를 바꾸면
     * <b>취소가 실패해도 끝난 것으로 보인다.</b>
     */
    @Transactional
    public Refund confirmRepaymentAndCancel(AuthPrincipal me, Long refundId) {
        Refund refund = refundRepository.findById(refundId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "환불 건을 찾을 수 없습니다."));
        me.requireAcademyScope(refund.getAcademy().getId());

        if (refund.getMethod() != RefundPayout.REPAY_AND_FULL_CANCEL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "재결제 후 전체취소로 처리된 건이 아닙니다.");
        }
        if (refund.getStatus() != RefundStatus.WAITING_REPAYMENT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "재결제 대기 상태가 아닙니다. (상태: %s)".formatted(refund.getStatus()));
        }

        refund.confirmRepayment(Instant.now(clock));

        Billing billing = refund.getBilling();
        PaymentTransaction tx = refund.getTransaction();
        if (tx == null) {
            refund.markFailed("기결제 거래를 찾을 수 없어 전체취소를 걸 수 없습니다.",
                    Instant.now(clock));
            return refund;
        }
        // ★ 전액 취소다 — 부분취소가 아니다
        cancelOnPg(refund, billing, tx, tx.getAmount());

        log.info("★ 재결제 확인 후 전체취소: refundId={}, 취소액={}, 처리자={}",
                refundId, tx.getAmount(), me.accountId());
        return refund;
    }

    private void cancelOnPg(Refund refund, Billing billing, PaymentTransaction tx, int amount) {
        if (tx.getPgTid() == null || tx.getPgTid().isBlank()) {
            // ⚠️ 데스크가 수기로 넣은 카드 수납이다 — PG 거래번호가 없어 취소를 걸 수 없다.
            //    실패로 남겨야 「환불했다」고 오인하지 않는다
            refund.markFailed("PG 거래번호가 없어 카드 취소를 걸 수 없습니다. "
                    + "단말기 수기 수납이면 단말에서 취소해 주세요.", Instant.now(clock));
            return;
        }
        String siteCd = pgSiteRepository
                .findForUse(billing.getAcademy().getId(), PgPurpose.TUITION, PgChannel.BUYLINK)
                .map(s -> s.getSiteCd())
                .orElse(null);
        if (siteCd == null) {
            refund.markFailed("PG 사이트코드가 등록되지 않았습니다.", Instant.now(clock));
            return;
        }
        try {
            var canceled = kcpClient.cancelPayment(new KcpBuyLinkClient.PaymentCancelCommand(
                    siteCd, tx.getPgTid(), amount, "환불"));
            tx.cancel(Instant.now(clock));
            billing.refreshStatus();
            refund.markDone(canceled.tno(), Instant.now(clock));
        } catch (Exception e) {
            // ★ 삼키지 않고 실패로 남긴다. 그리고 거래는 취소 표시하지 않는다 —
            //   PG 에서 안 돌아갔는데 우리 쪽만 취소로 두면 금액이 영구히 어긋난다
            log.error("카드 취소 실패: refundId={}, tid={}", refund.getId(), tx.getPgTid(), e);
            refund.markFailed(e.getMessage(), Instant.now(clock));
        }
    }

    private RefundCalculator.Result calculate(Billing billing, int usedDays) {
        if (billing.getServiceYear() == null || billing.getServiceMonth() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "대상 연·월이 없는 청구는 환불 계산을 할 수 없습니다.");
        }
        // ★ 그 달 교습일수로 계산한다. 달력 일수가 아니다 — 설·추석 당일이 빠져 있다
        int teachingDays = pricingService.teachingDays(billing.getServiceYear(),
                billing.getServiceMonth(), billing.getAcademy().getId());
        return RefundCalculator.calculate(billing.activeItems(), teachingDays, usedDays);
    }

    private Optional<PaymentTransaction> latestActive(Billing billing, PaymentMethod method) {
        return billing.getTransactions().stream()
                .filter(PaymentTransaction::isActive)
                .filter(t -> t.getMethod() == method)
                .max(Comparator.comparing(PaymentTransaction::getPaidAt));
    }

    private Billing requireBilling(AuthPrincipal me, Long billingId) {
        Billing billing = billingRepository.findById(billingId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "청구를 찾을 수 없습니다."));
        if (!me.canAccessAcademy(billing.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return billing;
    }
}
