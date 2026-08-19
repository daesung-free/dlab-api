package com.dlab.api.admin.billing;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingStatus;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 청구·수납 (F-4.8-1) — <b>최소분</b>.
 *
 * <p>키오스크 {@code getReceiptInfo}(3.29)에 내릴 데이터를 만드는 데 필요한 만큼이다.
 * 수납현황 화면(통계·미납자 알림·엑셀)과 청구기준 관리(F-4.10-5)는 블로커 대기 —
 * <b>E-3</b>(PG 스펙) · <b>I-25</b>(현금영수증) · <b>I-26</b>(환불 일할계산) · 할인 정책.
 *
 * <p>수납은 <b>수기 기록</b>이다. PG 연동은 없다.
 */
@Tag(name = "관리자 · 청구·수납 (F-4.8)")
@RestController
@RequestMapping("/api/v1/admin/billings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminBillingController {

    private final BillingService billingService;

    @GetMapping
    public ApiResponse<List<BillingResponse>> list(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId,
                                                   @RequestParam short year) {
        return ApiResponse.success(billingService.findByAcademy(me, academyId, year).stream()
                .map(BillingResponse::from).toList());
    }

    @GetMapping("/students/{enrollmentId}")
    public ApiResponse<List<BillingResponse>> byStudent(@CurrentAccount AuthPrincipal me,
                                                        @PathVariable Long enrollmentId) {
        return ApiResponse.success(billingService.findByStudent(me, enrollmentId).stream()
                .map(BillingResponse::from).toList());
    }

    /** 청구 생성. <b>할인은 값으로 받는다</b> — 할인 정책이 미확정이라 산출하지 않는다. */
    @PostMapping
    public ApiResponse<BillingResponse> create(@CurrentAccount AuthPrincipal me,
                                               @Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(BillingResponse.from(billingService.create(
                me, request.enrollmentId(), request.name(), request.billingType(),
                request.suppliedAmount(), request.discountOrZero(), request.dueDate())));
    }

    /** 수납 기록. 분납을 허용한다 — 완납되면 미납자 목록에서 빠진다. */
    @PostMapping("/{billingId}/payments")
    public ApiResponse<BillingResponse> pay(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long billingId,
                                            @Valid @RequestBody PayRequest request) {
        billingService.pay(me, billingId, request.amount(), request.method());
        return ApiResponse.success(BillingResponse.from(
                billingService.findByStudentBilling(me, billingId)));
    }

    /** 수납 취소. 거래를 지우지 않고 표시만 한다 — 이력이 사라지면 정산 추적이 끊긴다. */
    @DeleteMapping("/payments/{transactionId}")
    public ApiResponse<Void> cancelPayment(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long transactionId) {
        billingService.cancelPayment(me, transactionId);
        return ApiResponse.empty();
    }

    @DeleteMapping("/{billingId}")
    public ApiResponse<Void> cancel(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long billingId) {
        billingService.cancel(me, billingId);
        return ApiResponse.empty();
    }

    public record CreateRequest(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotBlank(message = "청구명은 필수입니다.") @Size(max = 100) String name,
            @NotNull(message = "청구 유형은 필수입니다.") BillingType billingType,
            @NotNull @Positive(message = "정가는 0보다 커야 합니다.") Integer suppliedAmount,
            Integer discountAmount,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {

        /** 할인 미입력은 0으로 본다 — 대부분의 청구에 할인이 없다. */
        public int discountOrZero() {
            return discountAmount == null ? 0 : discountAmount;
        }
    }

    public record PayRequest(
            @NotNull @Positive(message = "수납액은 0보다 커야 합니다.") Integer amount,
            @NotNull(message = "수납 수단은 필수입니다.") PaymentMethod method) {
    }

    /** @param unpaidAmount 미납액. 키오스크 {@code mi_amt}와 같은 값이다 */
    public record BillingResponse(Long id, String studentNo, String studentName,
                                  String name, BillingType billingType,
                                  int suppliedAmount, int discountAmount, int billedAmount,
                                  int receivedAmount, int unpaidAmount,
                                  LocalDate dueDate, BillingStatus status) {

        static BillingResponse from(Billing b) {
            return new BillingResponse(b.getId(),
                    b.getEnrollment().getStudentNo(),
                    b.getEnrollment().getStudent().getName(),
                    b.getName(), b.getBillingType(),
                    b.getSuppliedAmount(), b.getDiscountAmount(), b.getBilledAmount(),
                    b.receivedAmount(), b.unpaidAmount(),
                    b.getDueDate(), b.getStatus());
        }
    }
}
