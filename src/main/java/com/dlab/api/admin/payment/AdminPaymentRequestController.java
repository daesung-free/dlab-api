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
     * @param status  {@code CREATED}=링크만 만들어짐, {@code PAID}=결제 완료(Webhook 확정)
     * @param payUrl  학부모가 열 주소. 문자를 껐다면 화면이 이 값을 전달한다
     * @param tno     KCP 거래번호. 승인 후에만 있다
     */
    public record PaymentRequestResponse(Long id, Long billingId, String orderNo, int amount,
                                         String payMethod, String status, String payUrl,
                                         Instant expireAt, String tno, Instant approvedAt,
                                         String payDetail, String failReason) {

        static PaymentRequestResponse from(PaymentRequest r) {
            return new PaymentRequestResponse(r.getId(), r.getBilling().getId(), r.getOrderNo(),
                    r.getAmount(), r.getPayMethod().name(), r.getStatus().name(), r.getPayUrl(),
                    r.getExpireAt(), r.getTno(), r.getApprovedAt(), r.getPayDetail(),
                    r.getFailReason());
        }
    }
}
