package com.dlab.api.admin.tuition;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingStandard;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.service.BillingStandardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 청구기준 관리 (F-4.10-5 · 관리자 &gt; 수납관리 &gt; 청구기준 관리).
 *
 * <h2>화면 두 탭</h2>
 * <ul>
 *   <li><b>청구 기준</b> — {@code GET /billing-standards}. 항목·상태 필터가 있다</li>
 *   <li><b>환불 기준</b> — {@code GET /billing-standards/refund-rules}.
 *       <b>읽기 전용</b>이다 — 학원법 반환기준이라 학원이 정하는 값이 아니다</li>
 * </ul>
 *
 * <h2>★ 교습비 금액은 여기서 안 정한다</h2>
 * 학년 × 좌석유형으로 갈려 한 칸에 못 넣는다. 교습비 행은 {@code PRICE_MATRIX}로 두고
 * 화면은 {@code amountMin~amountMax} 범위를 보여준 뒤 <b>단가표
 * ({@code /tuition/prices})로 드릴다운</b>한다.
 *
 * <p>같은 이유로 {@code /tuition/prices}는 없어지지 않는다 — 이 화면의 교습비 행을
 * 편집하는 하위 화면이다.
 */
@Tag(name = "관리자 · 청구기준 (F-4.10-5)")
@RestController
@RequestMapping("/api/v1/admin/billing-standards")
@RequiredArgsConstructor
public class AdminBillingStandardController {

    private final BillingStandardService standardService;

    /**
     * 청구기준 목록.
     *
     * @param academyId 비우면 전 지점 공통 행
     * @param itemType  항목 필터. 비우면 전체
     * @param active    상태 필터. 비우면 사용중 + 중지 전부
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<BillingStandardService.Row>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) BillingItemType itemType,
            @RequestParam(required = false) Boolean active) {

        return ApiResponse.success(standardService.list(me, year, academyId, itemType, active));
    }

    /**
     * 환불 기준 — <b>조회만</b> 된다.
     *
     * <p>목업은 편집 목록으로 그렸지만 값이 <b>학원법 시행령 반환기준</b>이라 학원이
     * 바꿀 수 있는 것이 아니다. 실제 계산은 {@code RefundCalculator}가 하고 여기서는
     * 그 구현을 설명만 한다 — 두 곳에 값을 두면 화면과 계산이 갈린다.
     */
    @GetMapping("/refund-rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<BillingStandardService.RefundRule>> refundRules() {
        return ApiResponse.success(standardService.refundRules());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<BillingStandardService.Row> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody Create request) {

        BillingStandard saved = standardService.create(me, request.academyId(), request.year(),
                request.code(), request.itemType(), request.name(), request.roundName(),
                request.amountSource(), request.amount(), request.dueDesc(),
                request.paymentMethod(), request.sortOrderOrZero(), request.memo());

        return ApiResponse.success(single(me, saved));
    }

    /** 코드·항목·연도·지점은 바꾸지 않는다 — 바꿀 일이면 새 기준을 만드는 게 맞다. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<BillingStandardService.Row> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long id,
            @Valid @RequestBody Update request) {

        BillingStandard saved = standardService.update(me, id, request.name(),
                request.roundName(), request.amountSource(), request.amount(),
                request.dueDesc(), request.paymentMethod(), request.sortOrderOrZero(),
                request.memo());

        return ApiResponse.success(single(me, saved));
    }

    /** 사용/중지. 지난 기수 기준은 지우지 않고 내린다. */
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<BillingStandardService.Row> changeActive(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long id,
            @Valid @RequestBody ChangeActive request) {

        return ApiResponse.success(single(me, standardService.changeActive(me, id,
                request.active())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        standardService.delete(me, id);
        return ApiResponse.success(null);
    }

    /**
     * 방금 저장한 한 건을 목록과 같은 모양으로 돌려준다.
     *
     * <p>화면이 저장 응답을 그대로 행에 꽂기 때문에 모양이 다르면 교습비 행의
     * 금액 범위가 빈 채로 갱신된다.
     */
    private BillingStandardService.Row single(AuthPrincipal me, BillingStandard saved) {
        Long scope = saved.isCommon() ? null : saved.getAcademy().getId();
        return standardService.list(me, saved.getYear(), scope, null, null).stream()
                .filter(row -> row.id().equals(saved.getId()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 청구기준 등록.
     *
     * <p>{@code amountSource = PRICE_MATRIX}(교습비)면 {@code amount}를 비운다.
     * 값을 실어 보내면 저장 시 버려진다 — 단가표가 진실이다.
     */
    public record Create(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotBlank(message = "코드는 필수입니다.")
            @Size(max = 30, message = "코드는 30자 이하여야 합니다.") String code,
            @NotNull(message = "항목은 필수입니다.") BillingItemType itemType,
            @NotBlank(message = "청구 기준명은 필수입니다.")
            @Size(max = 100, message = "기준명은 100자 이하여야 합니다.") String name,
            @Size(max = 20, message = "기수는 20자 이하여야 합니다.") String roundName,
            @NotNull(message = "금액 방식은 필수입니다.") BillingStandard.AmountSource amountSource,
            Integer amount,
            @Size(max = 50, message = "청구 시점은 50자 이하여야 합니다.") String dueDesc,
            PaymentMethod paymentMethod,
            Short sortOrder,
            @Size(max = 200, message = "비고는 200자 이하여야 합니다.") String memo) {

        short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }

    public record Update(
            @NotBlank(message = "청구 기준명은 필수입니다.")
            @Size(max = 100, message = "기준명은 100자 이하여야 합니다.") String name,
            @Size(max = 20, message = "기수는 20자 이하여야 합니다.") String roundName,
            @NotNull(message = "금액 방식은 필수입니다.") BillingStandard.AmountSource amountSource,
            Integer amount,
            @Size(max = 50, message = "청구 시점은 50자 이하여야 합니다.") String dueDesc,
            PaymentMethod paymentMethod,
            Short sortOrder,
            @Size(max = 200, message = "비고는 200자 이하여야 합니다.") String memo) {

        short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "BillingStandardChangeActive")
    public record ChangeActive(@NotNull(message = "사용 여부는 필수입니다.") Boolean active) {
    }
}
