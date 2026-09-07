package com.dlab.api.admin.meal;

import jakarta.validation.constraints.NotNull;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealVendor;
import com.dlab.domain.meal.service.MealVendorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 급식업체·단가 관리 (F-4.5 · 0820 규정).
 *
 * <h2>업체는 전역, 연결은 지점별</h2>
 * 디온푸드 한 곳이 7개 지점을 담당한다. 업체 자체는 본사가 관리하고,
 * "우리 지점은 어느 업체이고 얼마인가"는 지점이 정한다.
 *
 * <h2>★ 단가를 넣지 않으면 청구를 만들 수 없다</h2>
 * 신청은 되지만 금액이 비어 있는 주문이 쌓인다. 지점마다 단가가 다르고(대구만 8,000원)
 * 바뀔 수 있어서 <b>기본값을 두지 않았다</b> — 임의값을 쓰면 틀린 금액이 주문에
 * 스냅샷으로 박힌다.
 */
@Tag(name = "관리자 · 급식업체·단가 (F-4.5)")
@RestController
@RequestMapping("/api/v1/admin/meal-vendors")
@RequiredArgsConstructor
public class AdminMealVendorController {

    private final MealVendorService vendorService;

    /** 업체 목록. 내린 업체는 빠진다 — 지워지지는 않는다(과거 주문의 정산 근거). */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<VendorView>> list() {
        return ApiResponse.success(vendorService.findAll().stream().map(VendorView::from).toList());
    }

    /** 업체 등록. 이름 중복은 거부한다. */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<VendorView> create(@Valid @RequestBody SaveVendor request) {
        return ApiResponse.success(VendorView.from(vendorService.create(
                request.name(), request.contactName(),
                request.contactPhone(), request.contactEmail())));
    }

    /** 연락처 수정. 비운 항목은 변경하지 않는다. */
    @PatchMapping("/{vendorId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<VendorView> update(@PathVariable Long vendorId,
                                          @RequestBody SaveVendor request) {
        return ApiResponse.success(VendorView.from(vendorService.update(
                vendorId, request.name(), request.contactName(),
                request.contactPhone(), request.contactEmail())));
    }

    /** 업체 내리기. <b>지우지 않는다</b> — 과거 주문이 어느 업체 것이었는지가 남아야 한다. */
    @DeleteMapping("/{vendorId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> deactivate(@PathVariable Long vendorId) {
        vendorService.deactivate(vendorId);
        return ApiResponse.empty();
    }

    /** 우리 지점 급식 설정 — 업체·단가·마감일수. */
    @GetMapping("/assignment")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<AssignmentView> assignment(@CurrentAccount AuthPrincipal me,
                                                  @RequestParam Long academyId,
                                                  @RequestParam short year) {
        return ApiResponse.success(AssignmentView.from(
                vendorService.policyOf(me, academyId, year)));
    }

    /**
     * 지점에 업체·단가 연결.
     *
     * <p>설정이 없는 지점이면 만들어서 연결한다. <b>단가는 필수</b>다 —
     * 없으면 그 지점 주문에 금액이 안 박혀 청구를 만들 수 없다.
     */
    @PutMapping("/assignment")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<AssignmentView> assign(@CurrentAccount AuthPrincipal me,
                                              @Valid @RequestBody MealVendorAssign request) {
        return ApiResponse.success(AssignmentView.from(vendorService.assignToAcademy(
                me, request.academyId(), request.year(),
                request.vendorId(), request.unitPrice())));
    }

    /**
     * @param contactEmail 환불정보 자동 발송 수신처. ⚠️ 발송 수단이 미확정이라 지금은
     *                     보관만 한다 — 환불계좌는 개인정보라 제3자 제공 동의 범위
     *                     확인이 먼저다
     */
    public record SaveVendor(
            @Size(max = 50) String name,
            @Size(max = 30) String contactName,
            @Size(max = 20) String contactPhone,
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 120) String contactEmail) {
    }

    public record MealVendorAssign(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "업체는 필수입니다.") Long vendorId,
            @Positive(message = "단가는 1원 이상이어야 합니다.") @NotNull(message = "단가는 필수입니다.") Integer unitPrice) {
    }

    public record VendorView(Long id, String name, String contactName,
                             String contactPhone, String contactEmail) {

        static VendorView from(MealVendor v) {
            return new VendorView(v.getId(), v.getName(), v.getContactName(),
                    v.getContactPhone(), v.getContactEmail());
        }
    }

    /** @param unitPrice 비어 있으면 청구를 만들 수 없는 상태다 */
    public record AssignmentView(Long academyId, short year, Long vendorId, String vendorName,
                                 Integer unitPrice, short deadlineDays, boolean priced) {

        static AssignmentView from(MealPolicy p) {
            return new AssignmentView(p.getAcademy().getId(), p.getYear(),
                    p.getVendor() == null ? null : p.getVendor().getId(),
                    p.getVendor() == null ? null : p.getVendor().getName(),
                    p.getUnitPrice(), p.getDeadlineDays(), p.isPriced());
        }
    }
}
