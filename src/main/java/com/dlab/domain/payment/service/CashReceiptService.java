package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.repository.CashReceiptRepository;
import com.dlab.domain.payment.repository.PaymentTransactionRepository;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.integration.pg.KcpBuyLinkClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현금영수증 발급·취소 (I-25).
 *
 * <h2>★ 카드 수납에는 발급하지 않는다</h2>
 * 카드사가 이미 소득공제를 처리하므로 이중이 된다. 계좌이체·가상계좌·현금만 대상이다.
 *
 * <h2>부가세는 여기서 한 번만 계산한다</h2>
 * 공급가액 = 총액 / 1.1 (원 단위 버림), 부가세 = 총액 − 공급가액.
 * <b>계산한 값을 저장</b>한다 — 나중에 다시 계산하면 절사 규칙이 바뀌었을 때 신고된 값과
 * 어긋나고, 국세청에 간 것은 그때 보낸 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashReceiptService {

    private static final DateTimeFormatter TRADE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CashReceiptRepository receiptRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PgSiteRepository pgSiteRepository;
    private final KcpBuyLinkClient client;
    private final Clock clock;

    /**
     * 발급.
     *
     * @param transactionId 대상 수납. <b>카드 수납이면 거절</b>한다
     * @param idInfo        소득공제면 휴대폰번호, 지출증빙이면 사업자번호
     */
    @Transactional
    public CashReceipt issue(AuthPrincipal me, Long transactionId, ReceiptPurpose purpose,
                             String idInfo) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "수납 내역을 찾을 수 없습니다."));

        Billing billing = transaction.getBilling();
        if (!me.canAccessAcademy(billing.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (!transaction.isActive()) {
            // 취소된 수납에 영수증을 내면 받지 않은 돈이 신고된다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "취소된 수납입니다.");
        }
        if (transaction.getMethod() == PaymentMethod.CARD) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "카드 수납은 현금영수증 대상이 아닙니다. 카드사가 소득공제를 처리합니다.");
        }
        receiptRepository.findIssuedByTransactionId(transactionId).ifPresent(r -> {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 발급된 영수증이 있습니다. 취소 후 다시 발급하세요.");
        });

        int amount = transaction.getAmount();
        int supply = amount * 10 / 11;      // 원 단위 버림(§2 절사 규칙)
        int tax = amount - supply;

        PgSite site = pgSiteRepository.findForUse(billing.getAcademy().getId(),
                        purposeOf(billing), PgChannel.CASH_RECEIPT)
                .orElseThrow(() -> new BusinessException(ErrorCode.PG_SITE_NOT_FOUND,
                        "현금영수증 사이트코드가 등록되지 않았습니다."));

        String orderNo = "C%d-%d".formatted(transactionId, Instant.now(clock).toEpochMilli());
        CashReceipt receipt = receiptRepository.save(new CashReceipt(
                billing, transaction, site, orderNo, purpose, idInfo, amount, supply, tax));

        try {
            var issued = client.issueCashReceipt(new KcpBuyLinkClient.CashReceiptCommand(
                    site.getSiteCd(), orderNo,
                    // ★ 발급 시각이 아니라 돈을 받은 시각이다 — 며칠 뒤 발급해도 원 거래일로 신고된다
                    tradeTime(transaction.getPaidAt()),
                    purpose.code(), idInfo, amount, supply, tax,
                    billing.getName(), billing.getEnrollment().getStudent().getName()));
            receipt.markIssued(issued.cashNo(), issued.receiptNo(), Instant.now(clock));
            log.info("현금영수증 발급: transactionId={}, cashNo={}", transactionId, issued.cashNo());
        } catch (BusinessException e) {
            // 실패도 행으로 남긴다 — 왜 안 나갔는지 화면에서 보여야 재시도 판단이 된다
            receipt.markFailed(e.getMessage());
            throw e;
        }
        return receipt;
    }

    /**
     * 취소.
     *
     * <p><b>행을 지우지 않는다.</b> 발급했다가 취소한 사실이 남아야 신고 내역과 대조할 수
     * 있다 — 지우면 "발급한 적 없음" 과 구분되지 않는다.
     */
    @Transactional
    public CashReceipt cancel(AuthPrincipal me, Long receiptId) {
        CashReceipt receipt = receiptRepository.findById(receiptId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "영수증을 찾을 수 없습니다."));
        if (!me.canAccessAcademy(receipt.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (!receipt.isIssued()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "발급 상태가 아닙니다.");
        }
        // ⚠️ KCP 취소 호출은 규격 확인 후 붙인다. 지금은 상태만 되돌려 이중 발급을 막는다
        receipt.markCanceled(Instant.now(clock));
        log.info("현금영수증 취소: receiptId={}, cashNo={}", receiptId, receipt.getCashNo());
        return receipt;
    }

    @Transactional(readOnly = true)
    public java.util.List<CashReceipt> byBilling(Long billingId) {
        return receiptRepository.findByBillingId(billingId);
    }

    /** 급식비 영수증은 업체 명의로 나가야 한다 — 학원 코드로 내면 학원 매출로 잡힌다 */
    private PgPurpose purposeOf(Billing billing) {
        return billing.getBillingType() == BillingType.MEAL ? PgPurpose.MEAL : PgPurpose.TUITION;
    }

    private String tradeTime(Instant paidAt) {
        Instant at = paidAt == null ? Instant.now(clock) : paidAt;
        return TRADE_TIME.format(at.atZone(ZoneId.of("Asia/Seoul")));
    }
}
