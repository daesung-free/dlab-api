package com.dlab.api.admin.payment;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.DiscountPolicy;
import com.dlab.domain.payment.entity.DiscountType;
import com.dlab.domain.payment.service.DiscountPolicyService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 교습비 할인 정책 관리 (1016 Q3·Q4).
 *
 * <h2>★★ 종류 목록이 미회신이어도 여기서 채울 수 있다</h2>
 * 규칙을 데이터로 두었으므로 <b>답이 오면 행만 넣으면 된다</b> — 코드 변경·마이그레이션이
 * 필요 없다. 0820 규정의 비율 5종은 시드로 있고 <b>전부 꺼져 있다.</b>
 *
 * <h2>★ 켜는 경로를 따로 둔다</h2>
 * 켜는 순간 청구 금액이 달라진다. 다른 값을 고치다가 함께 켜지면 안 된다.
 *
 * <h2>⚠️ 독서실비는 받지 않는다</h2>
 * 0820 규정상 할인이 없다. 통과시키면 <b>환불 차감이 정가보다 작아져 학원이 손해를 본다.</b>
 */
@Tag(name = "관리자 · 교습비 할인 정책")
@RestController
@RequestMapping("/api/v1/admin/discount-policies")
@RequiredArgsConstructor
public class AdminDiscountPolicyController {

    private final DiscountPolicyService service;

    /** 꺼진 것도 함께 내린다 — 「행이 없다」와 「꺼져 있다」가 구분돼야 한다. */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<List<DiscountPolicyView>> list(
            @RequestParam Short year,
            @RequestParam Long academyId) {
        return ApiResponse.success(service.list(year, academyId).stream()
                .map(DiscountPolicyView::from).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<DiscountPolicyView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(DiscountPolicyView.from(service.create(
                request.year(), request.academyId(), request.code(), request.name(),
                request.discountType(), request.discountValue(), request.targetItemType(),
                request.applyFromMonth(), request.applyToMonth(), request.note())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> change(@PathVariable Long id,
                                    @Valid @RequestBody ChangeRequest request) {
        service.change(id, request.name(), request.discountType(), request.discountValue(),
                request.targetItemType(), request.applyFromMonth(), request.applyToMonth(),
                request.note());
        return ApiResponse.empty();
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> changeActive(@PathVariable Long id,
                                          @Valid @RequestBody ActiveRequest request) {
        service.changeActive(id, request.active());
        return ApiResponse.empty();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.empty();
    }

    // ── 요청 ──────────────────────────────────────────────────

    @Schema(name = "DiscountPolicyCreateRequest")
    public record CreateRequest(
            @NotNull Short year,
            /** 비우면 전 지점 공통 */
            Long academyId,
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull DiscountType discountType,
            @PositiveOrZero int discountValue,
            /** 비우면 할인 가능한 항목 전부 */
            BillingItemType targetItemType,
            @Min(1) @Max(12) Short applyFromMonth,
            @Min(1) @Max(12) Short applyToMonth,
            @Size(max = 500) String note) {
    }

    @Schema(name = "DiscountPolicyChangeRequest")
    public record ChangeRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull DiscountType discountType,
            @PositiveOrZero int discountValue,
            BillingItemType targetItemType,
            @Min(1) @Max(12) Short applyFromMonth,
            @Min(1) @Max(12) Short applyToMonth,
            @Size(max = 500) String note) {
    }

    @Schema(name = "DiscountPolicyActiveRequest")
    public record ActiveRequest(@NotNull Boolean active) {
    }

    // ── 응답 ──────────────────────────────────────────────────

    @Schema(name = "DiscountPolicyView")
    public record DiscountPolicyView(Long id, Short year, Long academyId, String academyName,
                                     String code, String name,
                                     DiscountType discountType, int discountValue,
                                     BillingItemType targetItemType,
                                     Short applyFromMonth, Short applyToMonth,
                                     boolean active, String note) {

        static DiscountPolicyView from(DiscountPolicy p) {
            return new DiscountPolicyView(p.getId(), p.getYear(),
                    p.getAcademy() == null ? null : p.getAcademy().getId(),
                    p.getAcademy() == null ? null : p.getAcademy().getName(),
                    p.getCode(), p.getName(), p.getDiscountType(), p.getDiscountValue(),
                    p.getTargetItemType(), p.getApplyFromMonth(), p.getApplyToMonth(),
                    p.isActive(), p.getNote());
        }
    }
}
