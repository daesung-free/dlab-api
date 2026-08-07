package com.dlab.api.admin.meal;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealOrderStatus;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.service.MealAdminService;
import com.dlab.domain.meal.service.MealOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 급식 관리 (F-4.5).
 *
 * <p>화면 탭 그대로 — 월별 신청 현황 · 급식 일정 관리 · 결제·취소 내역.
 *
 * <p><b>배식 체크 탭은 없다</b> — 식사체크 방식(I-18)이 미확정이고,
 * QR 1회용 토큰이 앱 동적 QR(D-2)과 같은 건이다.
 */
@RestController
@RequestMapping("/api/v1/admin/meals")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminMealController {

    private final MealAdminService mealAdminService;
    private final MealOrderService mealOrderService;

    /** 월별 신청 현황 — 달력. */
    @GetMapping("/monthly")
    public ApiResponse<List<MealAdminService.DayStatus>> monthly(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam String month) {
        return ApiResponse.success(
                mealAdminService.monthly(me, academyId, YearMonth.parse(month)));
    }

    // ── 급식 일정 관리 ─────────────────────────────────────────

    @GetMapping("/closures")
    public ApiResponse<List<ClosureResponse>> closures(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam String month) {
        return ApiResponse.success(
                mealAdminService.closures(me, academyId, YearMonth.parse(month)).stream()
                        .map(ClosureResponse::from).toList());
    }

    /**
     * 중단일 등록.
     *
     * <p><b>이미 신청된 건이 함께 취소된다.</b> 응답의 {@code canceledCount}가
     * 환불 대상 건수다 — 화면이 이 값을 띄워 관리자가 알고 저장하게 한다.
     */
    @PostMapping("/closures")
    public ApiResponse<ClosureResponse> addClosure(@CurrentAccount AuthPrincipal me,
                                                   @Valid @RequestBody ClosureRequest request) {
        var result = mealAdminService.addClosure(
                me, request.academyId(), request.date(), request.reason());
        return ApiResponse.success(ClosureResponse.from(result.closure(), result.canceledCount()));
    }

    /** 중단일 해제. <b>취소된 신청은 되살리지 않는다</b> — 학생이 다시 신청한다. */
    @DeleteMapping("/closures/{closureId}")
    public ApiResponse<Void> removeClosure(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long closureId) {
        mealAdminService.removeClosure(me, closureId);
        return ApiResponse.empty();
    }

    /** 신청 마감 규칙 — 이용일 D-n. */
    @PutMapping("/policy")
    public ApiResponse<Short> saveDeadline(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody DeadlineRequest request) {
        return ApiResponse.success(mealAdminService.saveDeadline(
                me, request.academyId(), request.year(), request.deadlineDays())
                .getDeadlineDays());
    }

    /** 월 접수기간. <b>기간 밖·미등록이면 앱 신청 화면이 열리지 않는다.</b> */
    @GetMapping("/order-windows")
    public ApiResponse<List<WindowResponse>> windows(@CurrentAccount AuthPrincipal me,
                                                     @RequestParam(required = false) Long academyId,
                                                     @RequestParam short year) {
        return ApiResponse.success(mealAdminService.windows(me, academyId, year).stream()
                .map(WindowResponse::from).toList());
    }

    @PutMapping("/order-windows")
    public ApiResponse<WindowResponse> saveWindow(@CurrentAccount AuthPrincipal me,
                                                  @Valid @RequestBody WindowRequest request) {
        return ApiResponse.success(WindowResponse.from(mealAdminService.saveWindow(
                me, request.academyId(), YearMonth.parse(request.targetMonth()),
                request.startsOn(), request.endsOn())));
    }

    // ── 결제·취소 내역 ────────────────────────────────────────

    @GetMapping("/orders")
    public ApiResponse<List<OrderResponse>> orders(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId,
                                                   @RequestParam String month) {
        Long resolved = academyId != null ? academyId : me.academyScopeFilter();
        return ApiResponse.success(
                mealOrderService.findByMonth(resolved, YearMonth.parse(month)).stream()
                        .map(OrderResponse::from).toList());
    }

    /** 관리자 취소 — <b>기간 제한 없이 즉시</b>. */
    @DeleteMapping("/orders/items/{itemId}")
    public ApiResponse<Void> cancelItem(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long itemId) {
        mealOrderService.cancelByAdmin(me, itemId);
        return ApiResponse.empty();
    }

    // ── DTO ──────────────────────────────────────────────────

    public record ClosureRequest(
            Long academyId,
            @NotNull(message = "일자는 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @NotBlank(message = "중단 사유는 필수입니다.") @Size(max = 50) String reason) {
    }

    /** @param canceledCount 함께 취소된 신청 수(환불 대상). 조회 시에는 {@code null} */
    public record ClosureResponse(Long id, LocalDate date, String reason, Integer canceledCount) {

        static ClosureResponse from(MealClosure c) {
            return new ClosureResponse(c.getId(), c.getClosureDate(), c.getReason(), null);
        }

        static ClosureResponse from(MealClosure c, int canceledCount) {
            return new ClosureResponse(c.getId(), c.getClosureDate(), c.getReason(), canceledCount);
        }
    }

    public record DeadlineRequest(
            Long academyId,
            @NotNull short year,
            @NotNull(message = "마감 일수는 필수입니다.") Short deadlineDays) {
    }

    public record WindowRequest(
            Long academyId,
            @NotBlank(message = "대상 월은 필수입니다.") String targetMonth,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
    }

    public record WindowResponse(Long id, String targetMonth,
                                 LocalDate startsOn, LocalDate endsOn) {
        static WindowResponse from(com.dlab.domain.meal.entity.MealOrderWindow w) {
            return new WindowResponse(w.getId(), w.month().toString(),
                    w.getStartsOn(), w.getEndsOn());
        }
    }

    /** @param status 결제 라이프사이클. 결제 붙기 전까지 전부 {@code PENDING}이다 */
    public record OrderResponse(Long id, String studentNo, String studentName,
                                String targetMonth, MealOrderStatus status,
                                int activeCount, List<ItemResponse> items, Instant createdAt) {

        static OrderResponse from(MealOrder o) {
            return new OrderResponse(o.getId(),
                    o.getEnrollment().getStudentNo(),
                    o.getEnrollment().getStudent().getName(),
                    o.month().toString(), o.getStatus(),
                    o.activeItems().size(),
                    o.getItems().stream().map(ItemResponse::from).toList(),
                    o.getCreatedAt());
        }
    }

    public record ItemResponse(Long id, LocalDate mealDate, MealType mealType,
                               Instant canceledAt, String cancelPath) {
        static ItemResponse from(MealOrderItem i) {
            return new ItemResponse(i.getId(), i.getMealDate(), i.getMealType(),
                    i.getCanceledAt(),
                    i.getCancelPath() == null ? null : i.getCancelPath().name());
        }
    }
}
