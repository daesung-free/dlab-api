package com.dlab.api.admin.payment;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.CashReceipt;
import com.dlab.domain.payment.entity.ReceiptPurpose;
import com.dlab.domain.payment.service.CashReceiptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 웹 — 현금영수증 (I-25).
 *
 * <p><b>수납 단위로 발급한다.</b> 청구가 아니라 실제로 받은 건에 대해 내는 것이라,
 * 분할 납부면 영수증도 나뉜다.
 *
 * <p>★ <b>카드 수납은 대상이 아니다</b> — 카드사가 소득공제를 처리하므로 이중이 된다.
 */
@Tag(name = "관리자 · 현금영수증")
@RestController
@RequestMapping("/api/v1/admin/cash-receipts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminCashReceiptController {

    private final CashReceiptService cashReceiptService;

    /**
     * 발급.
     *
     * <p>⚠️ <b>용도에 따라 {@code idInfo} 의 의미가 바뀐다.</b> 소득공제면 휴대폰번호,
     * 지출증빙이면 사업자번호다 — 화면이 무엇을 받는지 분명히 해야 한다.
     */
    @PostMapping
    public ApiResponse<CashReceiptResponse> issue(@CurrentAccount AuthPrincipal me,
                                                  @RequestBody IssueRequest request) {
        return ApiResponse.success(CashReceiptResponse.from(cashReceiptService.issue(
                me, request.transactionId(), request.purposeOrDefault(), request.idInfo())));
    }

    /** 청구에 딸린 영수증 목록. 취소분도 함께 나온다 — 신고 내역과 대조해야 한다. */
    @GetMapping
    public ApiResponse<List<CashReceiptResponse>> byBilling(@RequestParam Long billingId) {
        return ApiResponse.success(cashReceiptService.byBilling(billingId).stream()
                .map(CashReceiptResponse::from).toList());
    }

    /** 취소. 행은 남는다 — "발급한 적 없음" 과 구분되어야 한다. */
    @PostMapping("/{receiptId}/cancel")
    public ApiResponse<CashReceiptResponse> cancel(@CurrentAccount AuthPrincipal me,
                                                   @PathVariable Long receiptId) {
        return ApiResponse.success(
                CashReceiptResponse.from(cashReceiptService.cancel(me, receiptId)));
    }

    /**
     * @param purpose 비우면 소득공제(개인)
     * @param idInfo  소득공제면 휴대폰번호, 지출증빙이면 사업자번호
     */
    public record IssueRequest(@NotNull Long transactionId, ReceiptPurpose purpose,
                               @NotBlank String idInfo) {

        ReceiptPurpose purposeOrDefault() {
            return purpose == null ? ReceiptPurpose.PERSONAL : purpose;
        }
    }

    /**
     * @param cashNo KCP 현금영수증 거래번호. 취소·조회의 키다
     * @param status {@code ISSUED} / {@code CANCELED} / {@code FAILED}
     */
    public record CashReceiptResponse(Long id, Long billingId, Long transactionId, String orderNo,
                                      String purpose, String idInfo, int amount,
                                      int supplyAmount, int taxAmount, String status,
                                      String cashNo, Instant issuedAt, Instant canceledAt,
                                      String failReason) {

        static CashReceiptResponse from(CashReceipt r) {
            return new CashReceiptResponse(r.getId(), r.getBilling().getId(),
                    r.getTransaction() == null ? null : r.getTransaction().getId(),
                    r.getOrderNo(), r.getPurpose().name(), r.getIdInfo(), r.getAmount(),
                    r.getSupplyAmount(), r.getTaxAmount(), r.getStatus().name(),
                    r.getCashNo(), r.getIssuedAt(), r.getCanceledAt(), r.getFailReason());
        }
    }
}
