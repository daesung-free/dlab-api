package com.dlab.api.admin.tuition;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingItem;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.service.TuitionBillingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 교습비 청구 발행 (F-4.8-1 · 0820 규정).
 *
 * <h2>금액을 손으로 넣지 않는다</h2>
 * 학생·월·좌석유형·할인율만 주면 <b>가격 마스터에서 계산</b>된다.
 * 데스크가 660,000÷27 같은 나눗셈을 할 일이 없다.
 *
 * <h2>★ 입학 처리는 청구가 두 건 나갈 수 있다</h2>
 * 1일 입학이 아니면 <b>당월 일할 + 다음달 정액</b>이다 —
 * 규정의 <i>"최초 입학시 1개월 미만으로 결제될 경우 다음달까지 함께 납부"</i>.
 * 화면에서 두 건이 뜨는 게 정상이므로 하나로 합쳐 보여주지 말 것.
 */
@Tag(name = "관리자 · 교습비 청구 발행 (F-4.8-1)")
@RestController
@RequestMapping("/api/v1/admin/tuition/billings")
@RequiredArgsConstructor
public class AdminTuitionBillingController {

    private final TuitionBillingService billingService;

    /**
     * 월 청구 발행.
     *
     * <p>{@code remainingDays}를 비우면 <b>월 정액</b>이다 — 1일 단가 × 일수로 하면
     * 절사분만큼 모자란 금액이 청구된다.
     */
    @PostMapping("/monthly")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<BillingView> issueMonthly(@CurrentAccount AuthPrincipal me,
                                                 @Valid @RequestBody IssueMonthly request) {
        return ApiResponse.success(BillingView.from(billingService.issueMonthly(
                me, request.enrollmentId(), request.yearMonth(),
                request.seatType(), request.discountRate(), request.remainingDays(),
                request.dueDate())));
    }

    /**
     * 입학 청구 발행. <b>1일 입학이 아니면 두 건</b>이 나간다.
     *
     * <p>{@code remainingDays}를 비우면 서버가 제안값을 쓴다 —
     * {@code GET /remaining-days}로 미리 받아 화면에서 고칠 수 있게 하는 편이 낫다.
     */
    @PostMapping("/admission")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<BillingView>> issueOnAdmission(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody IssueAdmission request) {

        return ApiResponse.success(billingService.issueOnAdmission(
                        me, request.enrollmentId(), request.admissionDate(), request.seatType(),
                        request.discountRate(), request.remainingDays(), request.dueDate())
                .stream().map(BillingView::from).toList());
    }

    /**
     * 남은 교습일수 <b>제안값</b>.
     *
     * <p>⚠️ 확정값이 아니다. 서버는 그 달 교습일수 총합만 알고 <b>어느 날이 휴원일인지는
     * 모른다.</b> 화면에서 데스크가 고칠 수 있어야 한다 — 서버가 확정하면 조용히 틀린다.
     */
    @GetMapping("/remaining-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<Integer> remainingDays(
            @RequestParam Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admissionDate) {
        return ApiResponse.success(billingService.suggestRemainingDays(academyId, admissionDate));
    }

    /**
     * @param month         청구 대상 월. {@code yyyy-MM} — <b>청구 1건 = 한 달분</b>이다
     * @param remainingDays 비우면 월 정액. 중도 입·퇴원이면 실제 다니는 교습일수
     */
    public record IssueMonthly(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotBlank(message = "월은 필수입니다.")
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])",
                    message = "월은 yyyy-MM 형식이어야 합니다.") String month,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @Min(0) @Max(100) int discountRate,
            @Min(1) @Max(31) Integer remainingDays,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }
    }

    public record IssueAdmission(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotNull(message = "입학일은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admissionDate,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @Min(0) @Max(100) int discountRate,
            @Min(1) @Max(31) Integer remainingDays,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
    }

    /**
     * @param items 교습비·독서실비. <b>환불 산식이 달라</b> 나눠 든다 —
     *              청구 금액({@code billedAmount})은 합계 한 줄이고 영수증도 그대로다
     */
    public record BillingView(Long id, String name, Short serviceYear, Short serviceMonth,
                              int suppliedAmount, int discountAmount, int billedAmount,
                              LocalDate dueDate, List<ItemView> items) {

        static BillingView from(Billing b) {
            return new BillingView(b.getId(), b.getName(), b.getServiceYear(), b.getServiceMonth(),
                    b.getSuppliedAmount(), b.getDiscountAmount(), b.getBilledAmount(),
                    b.getDueDate(), b.activeItems().stream().map(ItemView::from).toList());
        }
    }

    public record ItemView(String itemType, int suppliedAmount, int discountAmount,
                           int billedAmount) {

        static ItemView from(BillingItem i) {
            return new ItemView(i.getItemType().name(), i.getSuppliedAmount(),
                    i.getDiscountAmount(), i.getBilledAmount());
        }
    }
}
