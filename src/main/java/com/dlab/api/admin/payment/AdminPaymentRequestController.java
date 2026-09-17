package com.dlab.api.admin.payment;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.PayMethod;
import com.dlab.domain.payment.entity.PaymentRequest;
import com.dlab.domain.payment.repository.PaymentRequestRepository;
import com.dlab.domain.payment.service.PaymentRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 웹 — 결제 링크(바이링크).
 *
 * <p>데스크가 청구를 고르고 링크를 보내면, 학부모가 문자로 받은 주소에서 결제한다.
 * <b>완료는 이 API 가 아니라 Webhook 이 확정한다</b> — 링크를 보냈다고 수납이 잡히지 않는다.
 */
@Tag(name = "관리자 · 결제 링크")
@RestController
@RequestMapping("/api/v1/admin/payment-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminPaymentRequestController {

    private final PaymentRequestService paymentRequestService;
    private final PaymentRequestRepository requestRepository;

    /**
     * 결제 링크 생성.
     *
     * <p>금액은 <b>청구의 미납액</b>이다 — 부분 납부 뒤 남은 금액만 청구한다.
     *
     * <p>⚠️ 응답의 상태는 {@code CREATED} 다. <b>결제된 것이 아니라 링크가 만들어진 것</b>이고,
     * 화면도 그렇게 보여야 한다 — "결제 완료" 로 표시하면 데스크가 받은 줄 안다.
     */
    @PostMapping
    public ApiResponse<PaymentRequestResponse> create(
            @CurrentAccount AuthPrincipal me,
            @RequestBody CreateRequest request) {
        return ApiResponse.success(PaymentRequestResponse.from(
                paymentRequestService.createBuyLink(me, request.billingId(),
                        request.payMethodOrDefault(), request.sendSmsOrDefault())));
    }

    /**
     * 가상계좌 발급.
     *
     * <p>⚠️ <b>발급은 결제가 아닙니다.</b> 계좌번호가 나왔을 뿐이고 입금은 며칠 뒤에
     * 들어오거나 영영 안 들어온다 — 화면도 "발급됨" 으로 보여야 한다.
     *
     * @param bankCode 입금받을 은행 코드(KCP 은행코드표). 비우면 기본 은행
     * @param days     입금 기한(일). 비우면 7일
     */
    @PostMapping("/vbank")
    public ApiResponse<PaymentRequestResponse> issueVbank(
            @CurrentAccount AuthPrincipal me,
            @RequestBody VbankRequest request) {
        return ApiResponse.success(PaymentRequestResponse.from(
                paymentRequestService.issueVbank(me, request.billingId(),
                        request.bankCodeOrDefault(), request.daysOrDefault())));
    }

    /**
     * 단말기(POS) 승인 기록.
     *
     * <p>★ <b>이 API 는 결제를 하지 않는다.</b> 승인은 데스크 PC 의 SecureVCAT 이
     * 단말과 직접 해서 이미 끝났고, 여기서는 <b>그 결과를 적을 뿐</b>이다 —
     * 서버가 다시 요청하면 같은 금액이 두 번 승인된다.
     *
     * <p>그래서 Webhook 을 기다리지 않고 <b>호출 즉시 수납으로 잡힌다.</b>
     *
     * <p><b>승인번호로 중복을 막는다.</b> 저장에 실패해 다시 눌러도 한 번만 기록된다 —
     * 같은 승인이 두 번 잡히면 그 학생은 두 번 낸 것으로 남는다.
     */
    @PostMapping("/terminal")
    public ApiResponse<PaymentRequestResponse> recordTerminal(
            @CurrentAccount AuthPrincipal me,
            @RequestBody TerminalRequest request) {
        return ApiResponse.success(PaymentRequestResponse.from(
                paymentRequestService.recordTerminalApproval(me, request.billingId(),
                        request.amount(), request.approvalNo(), request.cardName(),
                        request.approvedAt())));
    }

    /** 청구의 결제 요청 이력. 링크를 몇 번 보냈는지, 어느 것이 결제됐는지 본다. */
    @GetMapping
    public ApiResponse<List<PaymentRequestResponse>> byBilling(@RequestParam Long billingId) {
        return ApiResponse.success(requestRepository.findByBillingId(billingId).stream()
                .map(PaymentRequestResponse::from).toList());
    }

    /**
     * @param payMethod 비우면 신용카드. 계좌이체는 {@code BANK}, 휴대폰은 {@code MOBX}
     * @param sendSms   비우면 보낸다. 끄면 URL 만 받아 화면에서 직접 전달할 수 있다
     */
    public record CreateRequest(@NotNull Long billingId, PayMethod payMethod, Boolean sendSms) {

        PayMethod payMethodOrDefault() {
            return payMethod == null ? PayMethod.CARD : payMethod;
        }

        boolean sendSmsOrDefault() {
            return sendSms == null || sendSms;
        }
    }

    /**
     * @param approvalNo 단말이 준 승인번호. <b>중복 저장을 막는 키</b>다
     * @param cardName   카드사명. 영수증·대사에 쓴다
     * @param approvedAt 단말 승인 시각. 비우면 서버 시각 — 오프라인 승인 뒤 늦게 저장하는
     *                   경우가 있어 단말 시각을 그대로 받는 편이 정확하다
     */
    public record TerminalRequest(@NotNull Long billingId, int amount,
                                  @NotNull String approvalNo, String cardName,
                                  Instant approvedAt) {
    }

    /**
     * @param bankCode 비우면 {@code BK26}(신한). 지점이 쓰는 은행으로 바꿀 수 있다
     * @param days     입금 기한. 비우면 7일 — 너무 길면 지난 달 청구가 살아 있다
     */
    public record VbankRequest(@NotNull Long billingId, String bankCode, Integer days) {

        String bankCodeOrDefault() {
            return bankCode == null || bankCode.isBlank() ? "BK26" : bankCode;
        }

        int daysOrDefault() {
            return days == null || days <= 0 ? 7 : days;
        }
    }

    /**
     * @param status  {@code CREATED}=링크만 만들어짐, {@code PAID}=결제 완료(Webhook 확정)
     * @param payUrl  학부모가 열 주소. 문자를 껐다면 화면이 이 값을 전달한다
     * @param tno     KCP 거래번호. 승인 후에만 있다
     */
    public record PaymentRequestResponse(Long id, Long billingId, String orderNo, int amount,
                                         String payMethod, String status, String payUrl,
                                         Instant expireAt, String tno, Instant approvedAt,
                                         String payDetail, String failReason,
                                         String vbankAccount, String vbankBankName,
                                         String vbankDepositor, String vbankRemitter) {

        static PaymentRequestResponse from(PaymentRequest r) {
            return new PaymentRequestResponse(r.getId(), r.getBilling().getId(), r.getOrderNo(),
                    r.getAmount(), r.getPayMethod().name(), r.getStatus().name(), r.getPayUrl(),
                    r.getExpireAt(), r.getTno(), r.getApprovedAt(), r.getPayDetail(),
                    r.getFailReason(), r.getVbankAccount(), r.getVbankBankName(),
                    r.getVbankDepositor(), r.getVbankRemitter());
        }
    }
}
